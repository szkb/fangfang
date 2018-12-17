package net.librec.recommender.cf.rating;

import net.librec.common.LibrecException;
import net.librec.math.structure.MatrixEntry;
import net.librec.recommender.MatrixFactorizationRecommender;

/**
 * @author szkb
 * @date 2018/12/08 14:48
 */
public class ModifiedPMFRecommender extends MatrixFactorizationRecommender {

    @Override
    protected void setup() throws LibrecException {
        super.setup();
    }

    @Override
    protected void trainModel() throws LibrecException {
        for (int iter = 1; iter <= numIterations; iter++) {

            loss = 0.0d;
            for (MatrixEntry me : trainMatrix) {
                int userId = me.row(); // user
                int itemId = me.column(); // item
                double realRating = me.get();

                double predictRating = predict(userId, itemId);
                double error = realRating - predictRating;

                loss += error * error;

                // update factors
                for (int factorId = 0; factorId < numFactors; factorId++) {
                    double userFactor = userFactors.get(userId, factorId), itemFactor = itemFactors.get(itemId, factorId);

                    userFactors.add(userId, factorId, learnRate * (error * itemFactor - regUser * userFactor));
                    itemFactors.add(itemId, factorId, learnRate * (error * userFactor - regItem * itemFactor));

                    loss += regUser * userFactor * userFactor + regItem * itemFactor * itemFactor;
                }
            }

            loss *= 0.5;
            if (isConverged(iter) && earlyStop) {
                break;
            }
            updateLRate(iter);
        }
    }

}
