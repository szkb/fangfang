/**
 * Copyright (C) 2016 LibRec
 * <p>
 * This file is part of LibRec.
 * LibRec is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * LibRec is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with LibRec. If not, see <http://www.gnu.org/licenses/>.
 */
package net.librec.recommender.context.rating;

import com.google.common.cache.LoadingCache;
import net.librec.annotation.ModelData;
import net.librec.common.LibrecException;
import net.librec.math.structure.DenseMatrix;
import net.librec.math.structure.DenseVector;
import net.librec.math.structure.MatrixEntry;
import net.librec.recommender.SocialRecommender;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Guo et al., <strong>TrustSVD: Collaborative Filtering with Both the Explicit and Implicit Influence of User Trust and
 * of Item Ratings</strong>, AAAI 2015.
 *
 * @author guoguibing and Keqiang Wang
 */
@ModelData({"isRating", "trustsvd", "userFactors", "itemFactors", "impItemFactors", "userBiases", "itemBiases", "socialMatrix", "trainMatrix", "impSocialMatrix"})
public class TrustSVDRecommender extends SocialRecommender {
    private Double explicitTrustWeight = 0.7;
    /**
     * impItemFactors denotes the implicit influence of items rated by user u in the past on the ratings of unknown items in the future.
     */
    private DenseMatrix impItemFactors;

    /**
     * the user-specific latent appender vector of users (trustees)trusted by user u
     */
    private DenseMatrix trusteeFactors;

    private DenseMatrix impTrusteeFactors;

    /**
     * weights of users(trustees) trusted by user u
     */
    private DenseVector trusteeWeights;//Wv

    private DenseVector impTrusteeWeights;


    /**
     * weights of users(trusters) who trust user u
     */
    private DenseVector trusterWeights;

    private DenseVector impTrusterWeights;

    /**
     * weights of items rated by user u
     */
    private DenseVector impItemWeights;

    /**
     * user biases and item biases
     */
    private DenseVector userBiases, itemBiases;

    private DenseVector trustBiases, trusteeBiases;

    /**
     * bias regularization
     */
    protected double regBias;

    /**
     * user-items cache, user-trustee cache
     */
    protected LoadingCache<Integer, List<Integer>> userItemsCache, userTrusteeCache;

    protected LoadingCache<Integer, List<Integer>> impUserTrusteeCache;


    /**
     * Guava cache configuration
     */
    protected static String cacheSpec;

    /**
     * initial the model
     *
     * @throws LibrecException if error occurs
     */
    @Override
    public void setup() throws LibrecException {
        super.setup();
//        userFactors.init(1.0);
//        itemFactors.init(1.0);
        regBias = conf.getDouble("rec.bias.regularization", 0.01);

        cacheSpec = conf.get("guava.cache.spec", "maximumSize=200,expireAfterAccess=2m");

        //initialize userBiases and itemBiases
        userBiases = new DenseVector(numUsers);
        itemBiases = new DenseVector(numItems);
        trustBiases = new DenseVector(numUsers);
        trusteeBiases = new DenseVector(numUsers);

        userBiases.init(initMean, initStd);
        itemBiases.init(initMean, initStd);
        trustBiases.init(initMean, initStd);
        trusteeBiases.init(initMean, initStd);


        //initialize trusteeFactors and impItemFactors
        trusteeFactors = new DenseMatrix(numUsers, numFactors);
        impItemFactors = new DenseMatrix(numItems, numFactors);

        impTrusteeFactors = new DenseMatrix(numUsers, numFactors);

        trusteeFactors.init(initMean, initStd);
        impItemFactors.init(initMean, initStd);

        impTrusteeFactors.init(initMean, initStd);


        //initialize trusteeWeights, trusterWeights, impItemWeights
        trusteeWeights = new DenseVector(numUsers);
        trusterWeights = new DenseVector(numUsers);
        impItemWeights = new DenseVector(numItems);

        impTrusteeWeights = new DenseVector(numUsers);
        impTrusterWeights = new DenseVector(numUsers);

        for (int userIdx = 0; userIdx < numUsers; userIdx++) {
            int userFriendCount = socialMatrix.columnSize(userIdx);
            trusteeWeights.set(userIdx, userFriendCount > 0 ? 1.0 / Math.sqrt(userFriendCount) : 1.0);

            userFriendCount = socialMatrix.rowSize(userIdx);
            trusterWeights.set(userIdx, userFriendCount > 0 ? 1.0 / Math.sqrt(userFriendCount) : 1.0);
        }

        for (int userIdx = 0; userIdx < numUsers; userIdx++) {
            int userFriendCount = impSocialMatrix.columnSize(userIdx);
            impTrusteeWeights.set(userIdx, userFriendCount > 0 ? 1.0 / Math.sqrt(userFriendCount) : 1.0);

            userFriendCount = impSocialMatrix.rowSize(userIdx);
            impTrusterWeights.set(userIdx, userFriendCount > 0 ? 1.0 / Math.sqrt(userFriendCount) : 1.0);
        }
        for (int itemIdx = 0; itemIdx < numItems; itemIdx++) {
            int itemUsersCount = trainMatrix.columnSize(itemIdx);
            impItemWeights.set(itemIdx, itemUsersCount > 0 ? 1.0 / Math.sqrt(itemUsersCount) : 1.0);
        }

        //initialize user-items cache, user-trustee cache
        userItemsCache = trainMatrix.rowColumnsCache(cacheSpec);
        userTrusteeCache = socialMatrix.rowColumnsCache(cacheSpec);

        // TODO 这里的理解
        impUserTrusteeCache = impSocialMatrix.rowColumnsCache(cacheSpec);
    }

    /**
     * train model process
     *
     * @throws LibrecException if error occurs
     */
    @Override
    protected void trainModel() throws LibrecException {
        for (int iter = 1; iter <= 180; iter++) {

            loss = 0.0d;

            // temp user Factors and trustee factors
            DenseMatrix tempUserFactors = new DenseMatrix(numUsers, numFactors);
            DenseMatrix trusteeTempFactors = new DenseMatrix(numUsers, numFactors);

            DenseMatrix impTrusteeTempFactors = new DenseMatrix(numUsers, numFactors);

            for (MatrixEntry matrixEntry : trainMatrix) {
                int userIdx = matrixEntry.row(); // user userIdx
                int itemIdx = matrixEntry.column(); // item itemIdx
                double realRating = matrixEntry.get(); // real rating on item itemIdx rated by user userIdx

                // To speed up, directly access the prediction instead of invoking "predictRating = predict(userIdx,itemIdx)"
                double userBiasValue = userBiases.get(userIdx);

                double itemBiasValue = itemBiases.get(itemIdx);
                double predictRating = globalMean + userBiasValue + itemBiasValue + DenseMatrix.rowMult(userFactors, userIdx, itemFactors, itemIdx);

                // get the implicit influence predict rating using items rated by user userIdx
                List<Integer> impItemsList = null;
                try {
                    impItemsList = userItemsCache.get(userIdx);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                if (impItemsList.size() > 0) {
                    double sum = 0;
                    for (int impItemIdx : impItemsList)
                        sum += DenseMatrix.rowMult(impItemFactors, impItemIdx, itemFactors, itemIdx);

                    predictRating += sum / Math.sqrt(impItemsList.size());
                }

                // the user-specific influence of users (trustees)trusted by user userIdx
                List<Integer> trusteesList = null;
                try {
                    trusteesList = userTrusteeCache.get(userIdx);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                if (trusteesList.size() > 0) {
                    double sum = 0.0;
                    for (int trusteeIdx : trusteesList)
                        sum += DenseMatrix.rowMult(trusteeFactors, trusteeIdx, itemFactors, itemIdx);

                    predictRating += explicitTrustWeight * sum / Math.sqrt(trusteesList.size());
                }

                // the user-specific influence of users (trustees)trusted by user userIdx
                List<Integer> impTrusteesList = null;
                try {
                    impTrusteesList = impUserTrusteeCache.get(userIdx);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                if (impTrusteesList.size() > 0) {
                    double sum = 0.0;
                    for (int impTrusteeIdx : impTrusteesList)
                        sum += DenseMatrix.rowMult(impTrusteeFactors, impTrusteeIdx, itemFactors, itemIdx);

                    predictRating += (1 - explicitTrustWeight) * sum / Math.sqrt(impTrusteesList.size());
                }

                double error = predictRating - realRating;

                loss += error * error;

                double userWeightDenom = Math.sqrt(impItemsList.size());
                double trusteeWeightDenom = Math.sqrt(trusteesList.size());
                double impTrusteeWeightDenom = Math.sqrt(impTrusteesList.size());


                double userWeight = 1.0 / userWeightDenom;
                double itemWeight = impItemWeights.get(itemIdx); // |Uj|

                // update factors
                // stochastic gradient descent sgd
                double sgd = error + regBias * userWeight * userBiasValue;
                userBiases.add(userIdx, -learnRate * sgd);

                sgd = error + regBias * itemWeight * itemBiasValue;
                itemBiases.add(itemIdx, -learnRate * sgd);

                loss += regBias * userWeight * userBiasValue * userBiasValue +
                        regBias * itemWeight * itemBiasValue * itemBiasValue;


                double[] sumImpItemsFactors = new double[numFactors];
                for (int factorIdx = 0; factorIdx < numFactors; factorIdx++) {
                    double sum = 0;
                    for (int impItemIdx : impItemsList)
                        sum += impItemFactors.get(impItemIdx, factorIdx);

                    sumImpItemsFactors[factorIdx] = userWeightDenom > 0 ? sum / userWeightDenom : sum;
                }

                double[] sumTrusteesFactors = new double[numFactors];
                for (int factorIdx = 0; factorIdx < numFactors; factorIdx++) {
                    double sum = 0;
                    for (int trusteeIdx : trusteesList)
                        sum += trusteeFactors.get(trusteeIdx, factorIdx);

                    sumTrusteesFactors[factorIdx] = trusteeWeightDenom > 0 ? sum / trusteeWeightDenom : sum;
                }

                double[] sumImpTrusteesFactors = new double[numFactors];
                for (int factorIdx = 0; factorIdx < numFactors; factorIdx++) {
                    double sum = 0;
                    for (int impTrusteeIdx : impTrusteesList)
                        sum += impTrusteeFactors.get(impTrusteeIdx, factorIdx);

                    sumImpTrusteesFactors[factorIdx] = impTrusteeWeightDenom > 0 ? sum / impTrusteeWeightDenom : sum;
                }

                for (int factorIdx = 0; factorIdx < numFactors; factorIdx++) {
                    double userFactorValue = userFactors.get(userIdx, factorIdx);
                    double itemFactorValue = itemFactors.get(itemIdx, factorIdx);

                    double deltaUser = error * itemFactorValue + regUser * userWeight * userFactorValue;
                    double deltaItem = error * (userFactorValue + sumImpItemsFactors[factorIdx]
                            + explicitTrustWeight * sumTrusteesFactors[factorIdx]
                            + (1 - explicitTrustWeight) * sumImpTrusteesFactors[factorIdx])
                            + regItem * itemWeight * itemFactorValue;

                    tempUserFactors.add(userIdx, factorIdx, deltaUser);
                    itemFactors.add(itemIdx, factorIdx, -learnRate * deltaItem);

                    loss += regUser * userWeight * userFactorValue * userFactorValue
                            + regItem * itemWeight * itemFactorValue * itemFactorValue;

                    for (int impItemIdx : impItemsList) {
                        double impItemFactorValue = impItemFactors.get(impItemIdx, factorIdx);

                        double impItemWeightValue = impItemWeights.get(impItemIdx);
                        double deltaImpItem = error * itemFactorValue / userWeightDenom + regItem * impItemWeightValue * impItemFactorValue;
                        impItemFactors.add(impItemIdx, factorIdx, -learnRate * deltaImpItem);

                        loss += regItem * impItemWeightValue * impItemFactorValue * impItemFactorValue;

                    }

                    // update trusteeTempFactors
                    for (int trusteeIdx : trusteesList) {
                        double trusteeFactorValue = trusteeFactors.get(trusteeIdx, factorIdx);
                        double trusteeWeightValue = trusteeWeights.get(trusteeIdx);

                        // TODO 这里的 deltaTrustee 应该还差个值吧 trusteeWeightValue这里有问题
                        // trusteeWeightValue 这个应该是TV的值
                        // v is the set of users who trust user v
                        double deltaTrustee = explicitTrustWeight * error * itemFactorValue / trusteeWeightDenom
                                + regUser * trusteeWeightValue * trusteeFactorValue;
                        trusteeTempFactors.add(trusteeIdx, factorIdx, deltaTrustee);

                        loss += regUser * trusteeWeightValue * trusteeFactorValue * trusteeFactorValue;
                    }

                    for (int impTrusteeIdx : impTrusteesList) {
                        double impTrusteeFactorValue = impTrusteeFactors.get(impTrusteeIdx, factorIdx);
                        double impTrusteeWeightValue = impTrusteeWeights.get(impTrusteeIdx);

                        // TODO 这里的 deltaTrustee 应该还差个值吧
                        // trusteeWeightValue 这个应该是TV的值
                        // v is the set of users who trust user v
                        double deltaImpTrustee = (1 - explicitTrustWeight) * error * itemFactorValue / impTrusteeWeightDenom
                                + regUser * impTrusteeWeightValue * impTrusteeFactorValue;
                        impTrusteeTempFactors.add(impTrusteeIdx, factorIdx, deltaImpTrustee);

                        loss += regUser * impTrusteeWeightValue * impTrusteeFactorValue * impTrusteeFactorValue;
                    }
                }
            }

            for (MatrixEntry socialMatrixEntry : socialMatrix) {
                int userIdx = socialMatrixEntry.row();
                int trusteeIdx = socialMatrixEntry.column();
                double socialValue = socialMatrixEntry.get();
                if (socialValue == 0)
                    continue;

                // TODO 这里分解真的合理吗？
                double predtictSocialValue = DenseMatrix.rowMult(userFactors, userIdx, trusteeFactors, trusteeIdx);
                double socialError = predtictSocialValue - socialValue;


                loss += regSocial * socialError * socialError;

                double deriValue = regSocial * socialError;

                double trusterWeightValue = trusterWeights.get(userIdx);
//                double sgd = socialError + regBias * trusterWeights.get(userIdx) * trustBiases.get(userIdx);
//                trustBiases.add(userIdx, -learnRate * sgd);
//
//                double sgd2 = socialError + regBias * trusteeWeights.get(userIdx) * trusteeBiases.get(userIdx);
//                trusteeBiases.add(userIdx, -learnRate * sgd2);
//
//                loss += regBias * trusterWeights.get(userIdx) * trustBiases.get(userIdx) * trustBiases.get(userIdx)
//                         + regBias * trusteeWeights.get(userIdx) * trusteeBiases.get(userIdx) * trusteeBiases.get(userIdx);

                for (int factorIdx = 0; factorIdx < numFactors; factorIdx++) {
                    double userFactorValue = userFactors.get(userIdx, factorIdx);
                    double trusteeFactorValue = trusteeFactors.get(trusteeIdx, factorIdx);

                    // TODO 这里都是用梯度下降法来求对应的值
                    // TODO tempUserFactors 指的就是pu，trusteeTempFactors 指的就是wv trusterWeightValue 表示|Tu|
                    tempUserFactors.add(userIdx, factorIdx, deriValue * trusteeFactorValue + regSocial * trusterWeightValue * userFactorValue);
                    trusteeTempFactors.add(trusteeIdx, factorIdx, deriValue * userFactorValue);

                    loss += regSocial * trusterWeightValue * userFactorValue * userFactorValue;
                }
            }

            for (MatrixEntry impMatrixEntry : impSocialMatrix) {
                int userIdx = impMatrixEntry.row();
                int impTrusteeIdx = impMatrixEntry.column();
                double impSocialValue = impMatrixEntry.get();
                if (impSocialValue == 0)
                    continue;

                // 这里怎么改, 不用改？？？！！！
                double predtictImpSocialValue = DenseMatrix.rowMult(userFactors, userIdx, impTrusteeFactors, impTrusteeIdx);
                double impSocialError = predtictImpSocialValue - impSocialValue;

                loss += regImpSocial * impSocialError * impSocialError;

                double deriValue = regImpSocial * impSocialError;

                // 这里还要改？？？
                double impTrusterWeightValue = impTrusterWeights.get(userIdx);

                for (int factorIdx = 0; factorIdx < numFactors; factorIdx++) {
                    double userFactorValue = userFactors.get(userIdx, factorIdx);

                    // TODO 这里要改
                    double impTrusteeFactorValue = impTrusteeFactors.get(impTrusteeIdx, factorIdx);

                    // TODO 这里都是用梯度下降法来求对应的值
                    // TODO tempUserFactors 指的就是pu，trusteeTempFactors 指的就是wv
                    tempUserFactors.add(userIdx, factorIdx, deriValue * impTrusteeFactorValue
                            + regImpSocial * impTrusterWeightValue * userFactorValue);
                    impTrusteeTempFactors.add(impTrusteeIdx, factorIdx, deriValue * userFactorValue);

                    loss += regImpSocial * impTrusterWeightValue * userFactorValue * userFactorValue;
                }
            }

            userFactors.addEqual(tempUserFactors.scale(-learnRate));
            trusteeFactors.addEqual(trusteeTempFactors.scale(-learnRate));
            impTrusteeFactors.addEqual(impTrusteeTempFactors.scale(-learnRate));

            loss *= 0.5d;


            if (isConverged(iter) && earlyStop) {
                break;
            }
            updateLRate(iter);
        }// end of training
    }

    /**
     * predict a specific rating for user userIdx on item itemIdx.
     *
     * @param userIdx user index
     * @param itemIdx item index
     * @return predictive rating for user userIdx on item itemIdx
     * @throws LibrecException if error occurs
     */
    protected double predict(int userIdx, int itemIdx) throws LibrecException {
        double predictRating = globalMean + userBiases.get(userIdx) + itemBiases.get(itemIdx) + DenseMatrix.rowMult(userFactors, userIdx, itemFactors, itemIdx);

        //the implicit influence of items rated by user in the past on the ratings of unknown items in the future.
        List<Integer> userItemsList = null;
        try {
            userItemsList = userItemsCache.get(userIdx);
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        if (userItemsList.size() > 0) {
            double sum = 0;
            for (int userItemIdx : userItemsList)

                //这就是Yi,用户的历史行为对用户评分预测的影响
                sum += DenseMatrix.rowMult(impItemFactors, userItemIdx, itemFactors, itemIdx);

            predictRating += sum / Math.sqrt(userItemsList.size());
        }

        // the user-specific influence of users (trustees)trusted by user u
        List<Integer> trusteeList = null;
        try {
            trusteeList = userTrusteeCache.get(userIdx);
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        if (trusteeList.size() > 0) {
            double sum = 0.0;
            for (int trusteeIdx : trusteeList)
                //完全和前面的类似，可以理解为信任自己的用户对自己评分可能产生的影响
                sum += DenseMatrix.rowMult(trusteeFactors, trusteeIdx, itemFactors, itemIdx);

            predictRating += explicitTrustWeight * sum / Math.sqrt(trusteeList.size());
        }

        // the user-specific influence of users (trustees)trusted by user userIdx
        List<Integer> impTrusteesList = null;
        try {
            impTrusteesList = impUserTrusteeCache.get(userIdx);
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        if (impTrusteesList.size() > 0) {
            double sum = 0.0;
            for (int impTrusteeIdx : impTrusteesList)
                sum += DenseMatrix.rowMult(impTrusteeFactors, impTrusteeIdx, itemFactors, itemIdx);

            predictRating += (1 - explicitTrustWeight) * sum / Math.sqrt(impTrusteesList.size());
        }

        return predictRating;
    }

    @Override
    protected double predict(int userIdx, int itemIdx, boolean bounded) throws LibrecException {
        double predictRating = predict(userIdx, itemIdx);

        return predictRating;
    }



}
