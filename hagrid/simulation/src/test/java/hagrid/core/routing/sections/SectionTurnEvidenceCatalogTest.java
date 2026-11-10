package hagrid.core.routing.sections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionTurnEvidenceCatalogTest {
 @TempDir Path dir;
 @Test void readsOptionalEvidenceAndCarriesItAcrossUnion() throws Exception {
  var file=dir.resolve("catalog.json");
  Files.writeString(file,"""
   {"schemaVersion":1,"carrierId":"c","depot":{"linkId":"f"},
    "links":{"f":{"fromNodeId":"a","toNodeId":"b","lengthMeters":100,"geometryLengthMeters":100,"orientedXY":[[0,0],[100,0]]}},
    "jobs":[{"jobId":"j","rawLinkId":"f","approaches":[{"linkId":"f","fraction":0.5}]}],
    "turnEvidence":[{"fromLinkId":"f","toLinkId":"r","kind":"NOT_TURN","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","fromNodeId":"a","toNodeId":"b","fromLengthMeters":100,"toLengthMeters":100}]}
   """);
  var catalog=PreparedHaltCatalog.read(file);assertThat(catalog.turnEvidence()).hasSize(1);
  var n=NetworkUtils.createNetwork();var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
  NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,100,10,1000,1);NetworkUtils.createAndAddLink(n,Id.createLinkId("r"),b,a,100,10,1000,1);
  var second=new PreparedHaltCatalog("d",catalog.links(),catalog.jobs(),catalog.depotLinkId(),catalog.turnEvidence());
  var split=SplitRoadNetworkBuilder.buildUnion(n,List.of(catalog,second)).get("c");assertThat(split.turnEvidence()).containsExactlyElementsOf(catalog.turnEvidence());
  var e=catalog.turnEvidence().getFirst();var conflict=new PreparedHaltCatalog.TurnEvidence(e.fromLinkId(),e.toLinkId(),"TURN",e.sourceSha256(),e.fromNodeId(),e.toNodeId(),e.fromLengthMeters(),e.toLengthMeters());
  var bad=new PreparedHaltCatalog("d",catalog.links(),catalog.jobs(),catalog.depotLinkId(),List.of(conflict));
  assertThatThrownBy(()->SplitRoadNetworkBuilder.buildUnion(n,List.of(catalog,bad))).isInstanceOf(IllegalArgumentException.class);
 }
}
