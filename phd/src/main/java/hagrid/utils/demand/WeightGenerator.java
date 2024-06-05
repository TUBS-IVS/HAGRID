package hagrid.utils.demand;

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
        defaultWeightClasses.put("Less than 0.1 kg", 0.021446032122536235);
        defaultWeightClasses.put("0.1 kg to 0.2 kg", 0.05205014910511699);
        defaultWeightClasses.put("0.2 kg to 0.5 kg", 0.21793679280014996);
        defaultWeightClasses.put("0.6 kg to 1 kg", 0.1673767492281103);
        defaultWeightClasses.put("1.1 kg to 2 kg", 0.16511961332205177);
        defaultWeightClasses.put("2.1 kg to 5 kg", 0.1342253505036545);
        defaultWeightClasses.put("More than 5 kg", 0.24184531291838005);

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
        defaultB2BWeightClasses.put("1 kg to 3 kg", 0.12);
        defaultB2BWeightClasses.put("3 kg to 10 kg", 0.33);
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
        defaultBetaParamsRegular.put("Less than 0.1 kg", 4.96289445355335);
        defaultAlphaParamsRegular.put("0.1 kg to 0.2 kg", 2.0);
        defaultBetaParamsRegular.put("0.1 kg to 0.2 kg", 5.310245414967183);
        defaultAlphaParamsRegular.put("0.2 kg to 0.5 kg", 2.507224168573464);
        defaultBetaParamsRegular.put("0.2 kg to 0.5 kg", 5.0);
        defaultAlphaParamsRegular.put("0.6 kg to 1 kg", 2.0);
        defaultBetaParamsRegular.put("0.6 kg to 1 kg", 5.0);
        defaultAlphaParamsRegular.put("1.1 kg to 2 kg", 2.0);
        defaultBetaParamsRegular.put("1.1 kg to 2 kg", 4.442763656468539);
        defaultAlphaParamsRegular.put("2.1 kg to 5 kg", 2.0);
        defaultBetaParamsRegular.put("2.1 kg to 5 kg", 5.619389445362472);
        defaultAlphaParamsRegular.put("More than 5 kg", 2.0);
        defaultBetaParamsRegular.put("More than 5 kg", 5.0);

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

    private Map<String, Double> weightClasses;
    private Map<String, double[]> weightRanges;
    private Map<String, Double> alphaParams;
    private Map<String, Double> betaParams;

    /**
     * Constructs a WeightGenerator with default parameters.
     */
    public WeightGenerator() {
        this.weightClasses = new HashMap<>(defaultWeightClasses);
        this.weightRanges = new HashMap<>(defaultWeightRanges);
        this.alphaParams = new HashMap<>(defaultAlphaParamsRegular);
        this.betaParams = new HashMap<>(defaultBetaParamsRegular);
    }

    /**
     * Constructs a WeightGenerator with custom parameters.
     *
     * @param weightClasses Custom weight classes and probabilities.
     * @param weightRanges  Custom weight ranges for each class.
     * @param alphaParams   Custom alpha parameters for the Beta distribution.
     * @param betaParams    Custom beta parameters for the Beta distribution.
     */
    public WeightGenerator(Map<String, Double> weightClasses, Map<String, double[]> weightRanges,
                           Map<String, Double> alphaParams, Map<String, Double> betaParams) {
        this.weightClasses = new HashMap<>(weightClasses);
        this.weightRanges = new HashMap<>(weightRanges);
        this.alphaParams = new HashMap<>(alphaParams);
        this.betaParams = new HashMap<>(betaParams);
    }

    /**
     * Generates a weight for a parcel based on the specified distributions and parameters.
     * It automatically selects the appropriate parameters for B2B or regular parcels.
     *
     * @param isB2B Whether the parcel is B2B.
     * @return The generated weight for the parcel.
     */
    public double generateWeight(boolean isB2B) {
        Map<String, Double> classes = isB2B ? defaultB2BWeightClasses : this.weightClasses;
        Map<String, double[]> ranges = isB2B ? defaultB2BWeightRanges : this.weightRanges;
        Map<String, Double> alphas = isB2B ? defaultAlphaParamsB2B : this.alphaParams;
        Map<String, Double> betas = isB2B ? defaultBetaParamsB2B : this.betaParams;

        double randVal = RANDOM.nextDouble();
        double cumulativeProbability = 0.0;
        for (Map.Entry<String, Double> entry : classes.entrySet()) {
            cumulativeProbability += entry.getValue();
            if (randVal < cumulativeProbability) {
                double[] range = ranges.get(entry.getKey());
                double low = range[0];
                double high = range[1];
                double alpha = alphas.get(entry.getKey());
                double beta = betas.get(entry.getKey());
                double weight = low + (high - low) * betaDistributionSample(alpha, beta);
                return Math.round(weight * 100.0) / 100.0;
            }
        }
        return Math.round(RANDOM.nextDouble() * 31.5 * 100.0) / 100.0;
    }

    /**
     * Samples from a beta distribution using the specified alpha and beta parameters.
     *
     * @param alpha The alpha parameter of the beta distribution.
     * @param beta  The beta parameter of the beta distribution.
     * @return A sample from the beta distribution.
     */
    private double betaDistributionSample(double alpha, double beta) {
        double sample1 = gammaDistributionSample(alpha, 1.0);
        double sample2 = gammaDistributionSample(beta, 1.0);
        return sample1 / (sample1 + sample2);
    }

    /**
     * Samples from a gamma distribution using the specified shape and scale parameters.
     *
     * @param shape The shape parameter of the gamma distribution.
     * @param scale The scale parameter of the gamma distribution.
     * @return A sample from the gamma distribution.
     */
    private double gammaDistributionSample(double shape, double scale) {
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

    /**
     * Sets custom weight classes and probabilities.
     *
     * @param weightClasses Custom weight classes and probabilities.
     */
    public void setWeightClasses(Map<String, Double> weightClasses) {
        this.weightClasses = new HashMap<>(weightClasses);
    }

    /**
     * Sets custom weight ranges.
     *
     * @param weightRanges Custom weight ranges for each class.
     */
    public void setWeightRanges(Map<String, double[]> weightRanges) {
        this.weightRanges = new HashMap<>(weightRanges);
    }

    /**
     * Sets custom alpha parameters for the Beta distribution.
     *
     * @param alphaParams Custom alpha parameters.
     */
    public void setAlphaParams(Map<String, Double> alphaParams) {
        this.alphaParams = new HashMap<>(alphaParams);
    }

    /**
     * Sets custom beta parameters for the Beta distribution.
     *
     * @param betaParams Custom beta parameters.
     */
    public void setBetaParams(Map<String, Double> betaParams) {
        this.betaParams = new HashMap<>(betaParams);
    }
}
