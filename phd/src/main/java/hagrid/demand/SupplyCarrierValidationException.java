package hagrid.demand;

    /**
     * Custom exception for supply carrier validation errors.
     */
    public class SupplyCarrierValidationException extends Exception {
        public SupplyCarrierValidationException(String message) {
            super(message);
        }
    }
