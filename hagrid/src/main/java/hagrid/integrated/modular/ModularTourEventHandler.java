package hagrid.integrated.modular;

import org.matsim.core.events.handler.EventHandler;

/** Typed handler for {@link ModularTourEvent} (custom-event reflection dispatch, DVRP pattern). */
public interface ModularTourEventHandler extends EventHandler {
    void handleEvent(ModularTourEvent event);
}
