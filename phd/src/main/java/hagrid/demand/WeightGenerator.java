package hagrid.demand;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The WeightGenerator class is responsible for generating parcel weights
 * based on specified distributions and parameters.
 */
public class WeightGenerator {

    private static final Random RANDOM = new Random();

    // Default weight classes and probabilities for regular parcels
    private static final Map<String, Double> defaultWeightClasses = new HashMap<>();
    private static final Map<String, double[]> defaultWeightRanges = new HashMap<>();

    // Default weight classes and probabilities for B2B parcels
    private static final Map<String, Double> defaultB2BWeightClasses = new HashMap<>();
    private static final Map<String, double[]> defaultB2BWeightRanges = new HashMap<>();

    // Default alpha and beta parameters for regular parcels
    private static final Map<String, Double> defaultAlphaParamsRegular = new HashMap<>();
    private static final Map<String, Double> defaultBetaParamsRegular = new HashMap<>();

    // Default alpha and beta parameters for B2B parcels
    private static final Map<String, Double> defaultAlphaParamsB2B = new HashMap<>();
    private static final Map<String, Double> defaultBetaParamsB2B = new HashMap<>();

    static {
        // Initialize default weight classes and probabilities for regular parcels
        defaultWeightClasses.put("Less than 0.1 kg", 0.0);
        defaultWeightClasses.put("0.1 kg to 0.2 kg", 0.054567713830592834);
        defaultWeightClasses.put("0.2 kg to 0.5 kg", 0.22964114415307915);
        defaultWeightClasses.put("0.6 kg to 1 kg", 0.21083063335977564);
        defaultWeightClasses.put("1.1 kg to 2 kg", 0.10181971176383549);
        defaultWeightClasses.put("2.1 kg to 5 kg", 0.15455914218317907);
        defaultWeightClasses.put("More than 5 kg", 0.24858165470953797);

        // Initialize default weight ranges for regular parcels
        defaultWeightRanges.put("Less than 0.1 kg", new double[]{0.0, 0.1});
        defaultWeightRanges.put("0.1 kg to 0.2 kg", new double[]{0.1, 0.2});
        defaultWeightRanges.put("0.2 kg to 0.5 kg", new double[]{0.2, 0.5});
        defaultWeightRanges.put("0.6 kg to 1 kg", new double[]{0.6, 1.0});
        defaultWeightRanges.put("1.1 kg to 2 kg", new double[]{1.1, 2.0});
        defaultWeightRanges.put("2.1 kg to 5 kg", new double[]{2.1, 5.0});
        defaultWeightRanges.put("More than 5 kg", new double[]{5.0, 31.5});

        // Initialize default weight classes and probabilities for B2B parcels
        defaultB2BWeightClasses.put("0.5 kg to 1 kg", 0.10);
        defaultB2BWeightClasses.put("1 kg to 3 kg", 0.20);
        defaultB2BWeightClasses.put("3 kg to 10 kg", 0.30);
        defaultB2BWeightClasses.put("10 kg to 20 kg", 0.25);
        defaultB2BWeightClasses.put("20 kg to 31.5 kg", 0.15);

        // Initialize default weight ranges for B2B parcels
        defaultB2BWeightRanges.put("0.5 kg to 1 kg", new double[]{0.5, 1.0});
        defaultB2BWeightRanges.put("1 kg to 3 kg", new double[]{1.0, 3.0});
        defaultB2BWeightRanges.put("3 kg to 10 kg", new double[]{3.0, 10.0});
        defaultB2BWeightRanges.put("10 kg to 20 kg", new double[]{10.0, 20.0});
        defaultB2BWeightRanges.put("20 kg to 31.5 kg", new double[]{20.0, 31.5});

        // Initialize default alpha and beta parameters for regular parcels
        defaultAlphaParamsRegular.put("Less than 0.1 kg", 2.0);
        defaultBetaParamsRegular.put("Less than 0.1 kg", 6.494638405475868);
        defaultAlphaParamsRegular.put("0.1 kg to 0.2 kg", 2.0);
        defaultBetaParamsRegular.put("0.1 kg to 0.2 kg", 4.5976909360673055);
        defaultAlphaParamsRegular.put("0.2 kg to 0.5 kg", 2.0);
        defaultBetaParamsRegular.put("0.2 kg to 0.5 kg", 5.0);
        defaultAlphaParamsRegular.put("0.6 kg to 1 kg", 2.0);
        defaultBetaParamsRegular.put("0.6 kg to 1 kg", 4.675048472856661);
        defaultAlphaParamsRegular.put("1.1 kg to 2 kg", 1.2652618772017774);
        defaultBetaParamsRegular.put("1.1 kg to 2 kg", 5.9755917656952615);
        defaultAlphaParamsRegular.put("2.1 kg to 5 kg", 2.1384210227061677);
        defaultBetaParamsRegular.put("2.1 kg to 5 kg", 5.365584318311542);
        defaultAlphaParamsRegular.put("More than 5 kg", 2.0);
        defaultBetaParamsRegular.put("More than 5 kg", 4.8977504923079795);

        // Initialize default alpha and beta parameters for B2B parcels
        defaultAlphaParamsB2B.put("0.5 kg to 1 kg", 2.0);
        defaultBetaParamsB2B.put("0.5 kg to 1 kg", 5.0);
        defaultAlphaParamsB2B.put("1 kg to 3 kg", 2.0);
        defaultBetaParamsB2B.put("1 kg to 3 kg", 5.0);
        defaultAlphaParamsB2B.put("3 kg to 10 kg", 2.0);
        defaultBetaParamsB2B.put("3 kg to 10 kg", 5.0);
        defaultAlphaParamsB2B.put("10 kg to 20 kg", 2.0);
        defaultBetaParamsB2B.put("10 kg to 20 kg", 5.0);
        defaultAlphaParamsB2B.put("20 kg to 31.5 kg", 2.0);
        defaultBetaParamsB2B.put("20 kg to 31.5 kg", 5.0);
    }

    /**
     * Generates a weight for a parcel based on the specified distributions and parameters.
     *
     * @param weightClasses    The weight classes and their probabilities.
     * @param weightRanges     The weight ranges for each class.
     * @param alphaParams      The alpha parameters for the beta distribution.
     * @param betaParams       The beta parameters for the beta distribution.
     * @return                 The generated weight for the parcel.
     */
    public static double generateWeight(Map<String, Double> weightClasses, Map<String, double[]> weightRanges,
                                        Map<String, Double> alphaParams, Map<String, Double> betaParams) {
        double randVal = RANDOM.nextDouble();
        double cumulativeProbability = 0.0;
        for (Map.Entry<String, Double> entry : weightClasses.entrySet()) {
            cumulativeProbability += entry.getValue();
            if (randVal < cumulativeProbability) {
                double[] range = weightRanges.get(entry.getKey());
                double low = range[0];
                double high = range[1];
                double alpha = alphaParams.get(entry.getKey());
                double beta = betaParams.get(entry.getKey());
                double weight = low + (high - low) * betaDistributionSample(alpha, beta);
                return Math.round(weight * 100.0) / 100.0;
            }
        }
        return Math.round(RANDOM.nextDouble() * 31.5 * 100.0) / 100.0;
    }

    /**
     * Samples from a beta distribution using the specified alpha and beta parameters.
     *
     * @param alpha  The alpha parameter of the beta distribution.
     * @param beta   The beta parameter of the beta distribution.
     * @return       A sample from the beta distribution.
     */
    private static double betaDistributionSample(double alpha, double beta) {
        double sample1 = gammaDistributionSample(alpha, 1.0);
        double sample2 = gammaDistributionSample(beta, 1.0);
        return sample1 / (sample1 + sample2);
    }

    /**
     * Samples from a gamma distribution using the specified shape and scale parameters.
     *
     * @param shape  The shape parameter of the gamma distribution.
     * @param scale  The scale parameter of the gamma distribution.
     * @return       A sample from the gamma distribution.
     */
    private static double gammaDistributionSample(double shape, double scale) {
        if (shape < 1) {
            shape += 1;
            double u = RANDOM.nextDouble();
            return gammaDistributionSample(shape, scale) * Math.pow(u, 1.0 / shape);
        }

        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x, v;
            do {
                x = RANDOM.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = RANDOM.nextDouble();
            if (u < 1 - 0.0331 * (x * x) * (x * x) || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
                return d * v * scale;
            }
        }
    }

    // Getter methods for accessing the default parameters

    public static Map<String, Double> getDefaultWeightClasses() {
        return defaultWeightClasses;
    }

    public static Map<String, double[]> getDefaultWeightRanges() {
        return defaultWeightRanges;
    }

    public static Map<String, Double> getDefaultB2BWeightClasses() {
        return defaultB2BWeightClasses;
    }

    public static Map<String, double[]> getDefaultB2BWeightRanges() {
        return defaultB2BWeightRanges;
    }

    public static Map<String, Double> getDefaultAlphaParamsRegular() {
        return defaultAlphaParamsRegular;
    }

    public static Map<String, Double> getDefaultBetaParamsRegular() {
        return defaultBetaParamsRegular;
    }

    public static Map<String, Double> getDefaultAlphaParamsB2B() {
        return defaultAlphaParamsB2B;
    }

    public static Map<String, Double> getDefaultBetaParamsB2B() {
        return defaultBetaParamsB2B;
    }
}
