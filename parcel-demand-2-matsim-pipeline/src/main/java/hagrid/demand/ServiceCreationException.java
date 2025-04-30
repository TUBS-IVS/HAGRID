package hagrid.demand;

    /**
     * Exception thrown when there is an error creating a CarrierService.
     */
    public class ServiceCreationException extends Exception {
        public ServiceCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }