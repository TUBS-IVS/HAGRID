package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.io.*;
import java.lang.ref.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Temporary controller profiles share one owned immutable graph; explicit exports remain complete. */
public final class SharedRoutingContextArchives implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String VERSION="hagrid-runtime-profile-v1";
    private record Reference(String version,String owner,String fingerprint,String graphFingerprint,
                             ReplayConfiguration config,List<ReplayContextArchive.Profile> profiles) {}
    private record Checkpoint(Path path,String sha256) {}
    private static final ReferenceQueue<SharedRoutingContextArchives> QUEUE=new ReferenceQueue<>();
    private static final Map<String,Owner> OWNERS=new HashMap<>();
    private static final class Owner extends WeakReference<SharedRoutingContextArchives> {
        final String id;
        Owner(String id,SharedRoutingContextArchives value){super(value,QUEUE);this.id=id;}
    }
    private final String id=UUID.randomUUID().toString();
    private final Map<String,Set<Path>> saved=new LinkedHashMap<>();
    private final Map<String,Checkpoint> checkpoints=new LinkedHashMap<>();
    private final Set<Path> ownedGraphs=new LinkedHashSet<>();
    private final Set<Path> ownedGraphDirectories=new LinkedHashSet<>();
    private RoutingNetworkSnapshot graph;
    private boolean closed;

    public SharedRoutingContextArchives(){synchronized(OWNERS){reap();OWNERS.put(id,new Owner(id,this));}}
    private static void reap(){Owner old;while((old=(Owner)QUEUE.poll())!=null)OWNERS.remove(old.id,old);}

    public synchronized Path save(Path root,RoutingContext context)throws IOException {
        requireOpen();
        try(var lease=context.acquireQuery()) {
            var frozen=(RoutingNetworkSnapshot)context.network();
            prune();
            if(graph!=null&&!graph.fingerprint().equals(frozen.fingerprint()))checkpointCurrentGraph();
            graph=frozen;
            Path profiles=root.toAbsolutePath().normalize().resolve(".contexts");Files.createDirectories(profiles);
            Path complete=profiles.resolve(context.fingerprint()+".zip");
            if(Files.exists(complete)) {
                try(var restored=read(complete)){ReplayContextArchive.requireFingerprint(restored,context.fingerprint());}
                return complete;
            }
            var types=context.vehicleProfiles().values().stream().sorted(Comparator.comparing(p->p.typeId().toString()))
                    .map(p->new ReplayContextArchive.Profile(p.typeId().toString(),p.mode(),p.maximumVelocityMps(),p.fixedCost(),p.costPerMeter(),p.costPerSecond())).toList();
            var metadata=new Reference(VERSION,id,context.fingerprint(),frozen.fingerprint(),ReplayConfiguration.of(context.config()),types);
            Path pending=Files.createTempFile(profiles,".profile-",".pending");
            try {
                try(var zip=new ZipOutputStream(Files.newOutputStream(pending))) {
                    zip.putNextEntry(new ZipEntry("context.json"));zip.write(JSON.writeValueAsBytes(metadata));zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("snapshot.bin"));
                    var data=new DataOutputStream(new BufferedOutputStream(zip));context.snapshot().write(data);data.flush();zip.closeEntry();
                }
                try(var restored=read(pending)){ReplayContextArchive.requireFingerprint(restored,context.fingerprint());}
                boolean published;
                synchronized(ReplayContextArchive.publicationLock(complete)) {
                    // A concurrent complete export must never be replaced by a runtime profile.
                    published=!Files.exists(complete);
                    if(published)Files.move(pending,complete,StandardCopyOption.ATOMIC_MOVE);
                }
                // Read outside the publication lock: this may acquire an owner monitor.
                if(!published)try(var restored=read(complete)){ReplayContextArchive.requireFingerprint(restored,context.fingerprint());}
                saved.computeIfAbsent(frozen.fingerprint(),unused->new LinkedHashSet<>()).add(complete);return complete;
            }finally {Files.deleteIfExists(pending);}
        }
    }

    /** Public complete-reader compatibility for active temporary profiles, never a silent free-speed fallback. */
    static RoutingContext readTemporary(Path archive)throws IOException {
        Reference reference=reference(archive);
        if(reference==null)return ReplayContextArchive.readComplete(archive);
        SharedRoutingContextArchives owner;
        synchronized(OWNERS){reap();var entry=OWNERS.get(reference.owner());owner=entry==null?null:entry.get();}
        if(owner==null) {
            if(ReplayContextArchive.isCompleteArchive(archive))return ReplayContextArchive.readComplete(archive);
            throw new IOException("Temporary routing profile owner is unavailable; use a complete replay export");
        }
        return owner.read(archive);
    }
    private static Reference reference(Path archive)throws IOException {
        synchronized(ReplayContextArchive.publicationLock(archive)) {
        try(var zip=new ZipFile(archive.toFile())) {
            // Inspect format and read its reference from the same open ZIP across atomic upgrades.
            if(zip.getEntry("network.xml")!=null)return null;
            if(zip.size()!=2||zip.getEntry("context.json")==null||zip.getEntry("snapshot.bin")==null)
                throw new IOException("Incomplete temporary routing profile");
            Reference result;
            try(var in=zip.getInputStream(zip.getEntry("context.json"))){result=JSON.readValue(in,Reference.class);}
            if(!VERSION.equals(result.version())||result.owner()==null||result.graphFingerprint()==null||!result.graphFingerprint().matches("[a-f0-9]{64}"))
                throw new IOException("Invalid temporary routing profile reference");
            return result;
        }
    }
        }
    public synchronized RoutingContext read(Path archive)throws IOException {
        requireOpen();
        var metadata=reference(archive);
        if(metadata==null)return ReplayContextArchive.readComplete(archive);
        if(!id.equals(metadata.owner()))throw new IOException("Temporary routing profile belongs to another owner");
        if(graph!=null&&graph.fingerprint().equals(metadata.graphFingerprint()))return restore(archive,metadata,graph);
        var checkpoint=checkpoints.get(metadata.graphFingerprint());
        if(checkpoint==null)throw new IOException("Missing checkpoint for temporary routing graph");
        if(!checkpoint.sha256().equals(OptimizationResultStore.digest(checkpoint.path())))throw new IOException("Routing graph checkpoint checksum mismatch");
        try(var complete=ReplayContextArchive.readComplete(checkpoint.path())) {
            var frozen=(RoutingNetworkSnapshot)complete.network();
            if(!frozen.fingerprint().equals(metadata.graphFingerprint()))throw new IOException("Routing graph checkpoint fingerprint mismatch");
            if(!checkpoint.sha256().equals(OptimizationResultStore.digest(checkpoint.path())))throw new IOException("Routing graph checkpoint changed during restore");
            return restore(archive,metadata,frozen);
        }
    }
    private RoutingContext restore(Path archive,Reference metadata,RoutingNetworkSnapshot frozen)throws IOException {
        TravelTimeSnapshot snapshot;
        synchronized(ReplayContextArchive.publicationLock(archive)) {
            try(var zip=new ZipFile(archive.toFile());var in=new DataInputStream(new BufferedInputStream(zip.getInputStream(zip.getEntry("snapshot.bin"))))){snapshot=TravelTimeSnapshot.read(in,frozen);}
        }
        var types=new CarrierVehicleTypes();
        for(var p:metadata.profiles()) {
            var type=VehicleUtils.createVehicleType(Id.create(p.id(),VehicleType.class),p.mode());
            type.setMaximumVelocity(p.velocity());type.getCostInformation().setFixedCost(p.fixed()).setCostsPerMeter(p.meter()).setCostsPerSecond(p.second());
            if(types.getVehicleTypes().putIfAbsent(type.getId(),type)!=null)throw new IOException("Duplicate temporary vehicle profile");
        }
        var restored=RoutingContext.create(frozen,types,snapshot,metadata.config().restore());
        try {ReplayContextArchive.requireFingerprint(restored,metadata.fingerprint());return restored;}
        catch(IOException|RuntimeException failure){restored.close();throw failure;}
    }
    private void checkpointCurrentGraph()throws IOException {
        String fingerprint=graph.fingerprint();
        if(checkpoints.containsKey(fingerprint))return;
        var existing=saved.getOrDefault(fingerprint,Set.of()).stream().filter(Files::isRegularFile).findFirst();
        if(existing.isEmpty())return;
        Path parent=existing.get().getParent().getParent();
        try(var context=read(existing.get())) {
            Path directory=parent.resolve(".graphs/.contexts");
            if(!Files.exists(directory))ownedGraphDirectories.add(directory);
            Path expected=directory.resolve(context.fingerprint()+".zip");boolean existed=Files.exists(expected);
            Path checkpoint=ReplayContextArchive.save(parent.resolve(".graphs"),context);
            if(!existed)ownedGraphs.add(checkpoint);
            checkpoints.put(fingerprint,new Checkpoint(checkpoint,OptimizationResultStore.digest(checkpoint)));
        }
    }
    private void prune()throws IOException {
        for(var paths:saved.values())paths.removeIf(p->!Files.exists(p));
        saved.entrySet().removeIf(e->e.getValue().isEmpty());
        for(var key:List.copyOf(checkpoints.keySet()))if(!saved.containsKey(key)) {
            var checkpoint=checkpoints.remove(key);if(ownedGraphs.remove(checkpoint.path()))ReplayContextArchive.deleteArchive(checkpoint.path());
        }
    }
    /** Explicit portable export retains the original self-contained archive and round-trip checks. */
    public synchronized Path exportComplete(Path profile,Path parent)throws IOException {
        try(var context=read(profile)){return ReplayContextArchive.save(parent,context);}
    }
    public synchronized void deleteOwnedGraphs()throws IOException {
        for(var path:ownedGraphs)ReplayContextArchive.deleteArchive(path);
        for(var parent:ownedGraphDirectories)Files.deleteIfExists(parent);
        ownedGraphs.clear();ownedGraphDirectories.clear();checkpoints.clear();saved.clear();
    }
    private void requireOpen(){if(closed)throw new IllegalStateException("Temporary routing archives are closed");}
    public synchronized void close(){closed=true;graph=null;synchronized(OWNERS){OWNERS.remove(id);reap();}}
}
