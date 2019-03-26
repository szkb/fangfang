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
package net.librec.recommender;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import net.librec.common.LibrecException;
import net.librec.data.convertor.appender.SocialDataAppender;
import net.librec.math.algorithm.Maths;
import net.librec.math.structure.SparseMatrix;
import net.librec.math.structure.SparseVector;
import net.librec.math.structure.SymmMatrix;
import net.librec.math.structure.VectorEntry;
import net.librec.util.Lists;

import java.util.*;

/**
 * Social Recommender
 *
 * @author Keqiang Wang
 */
public abstract class SocialRecommender extends MatrixFactorizationRecommender {
    /**
     * socialMatrix: social rate matrix, indicating a user is connecting to a number of other users
     */
    protected SparseMatrix socialMatrix;

    protected SparseMatrix tranSocialMatrix;

    protected SparseMatrix impSocialMatrix;

    protected SparseMatrix diSimilarityMatrix;

    /**
     * social regularization
     */
    protected float regSocial;

    protected float regImpSocial = 1.0f;
//    protected float regImpSocial = 1.0f;

    protected float impSocialWeight = 0.8f;

    protected float socialWeight = 0.8f;

    private int knn;

    private SymmMatrix similarityMatrix;
    private List<Map.Entry<Integer, Double>>[] userSimilarityList;


    @Override
    public void setup() throws LibrecException {
        super.setup();
        regSocial = conf.getFloat("rec.social.regularization", 0.01f);
        // social path for the socialMatrix
        socialMatrix = ((SocialDataAppender) getDataModel().getDataAppender()).getUserAppender();

//        tranSocialMatrix = ((SocialDataAppender) getDataModel().getDataAppender()).getTransPositionUserAppender();
//        diSimilarityMatrix = socialMatrix;
        searchDisimilarityList();
//        impSocialMatrix = ((SocialDataAppender) getDataModel().getDataAppender()).getTransPositionUserAppender();
//        impSocialMatrix = ((SocialDataAppender) getDataModel().getDataAppender()).getImpUserAppender();
////        impSocialMatrix = new SparseMatrix(socialMatrix);
//
////        Table<Integer, Integer, Double> dataTableImpSocialMatrix = HashBasedTable.create();
////        dataTableImpSocialMatrix = impSocialMatrix.getDataTable();
////
//        for (int userIdx = 0; userIdx < numUsers; userIdx++) {
//            for (int userIdj = 0; userIdj < numUsers; userIdj++) {
//                if (impSocialMatrix.contains(userIdx, userIdj) && impSocialMatrix.get(userIdx, userIdj) > 0) {
//                    impSocialMatrix.set(userIdx, userIdj, socialWeight * impSocialMatrix.get(userIdx, userIdj)
//                            + (1 - socialWeight) * similarity(userIdx, userIdj));
//                }
//            }
//        }
////                    dataTableImpSocialMatrix.put(userIdx, userIdj, impSocialWeight * impSocialMatrix.get(userIdx, userIdj)
////                            + (1 - impSocialWeight) * similarity(userIdx, userIdj));
//                }
////                } else {
////                    if (socialMatrix.contains(userIdx, userIdj)) {
////                        dataTableImpSocialMatrix.put(userIdx, userIdj, socialWeight * socialMatrix.get(userIdx, userIdj)
////                                + (1 - socialWeight) * similarity(userIdx, userIdj));                    }
////                }
//            }
//        }
    }

    // 寻找相似度
    protected double similarity(int userIdx, int userIdj) {
        knn = conf.getInt("rec.neighbors.knn.number");
        similarityMatrix = context.getSimilarity().getSimilarityMatrix();
        if (!(null != userSimilarityList && userSimilarityList.length > 0)) {
            createUserSimilarityList();
        }
        List<Map.Entry<Integer, Double>> nns = new ArrayList<>();

        Lists.sortList(userSimilarityList[userIdx]);
        List<Map.Entry<Integer, Double>> simList = userSimilarityList[userIdx];

        int count = 0;
        // 这个的目的是相似度用户也要评价同一个项目？？
//        Set<Integer> userSet = trainMatrix.getRowsSet(itemIdx);
//        for (Map.Entry<Integer, Double> userRatingEntry : simList) {
//            int similarUserIdx = userRatingEntry.getKey();
////            if (!userSet.contains(similarUserIdx)) {
////                continue;
////            }
//            double sim = userRatingEntry.getValue();
//            if (sim > 0) {
//                nns.add(userRatingEntry);
//                count++;
//            }
//            if (count == knn) {
//                break;
//            }
//        }

        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            if (trainMatrix.get(userIdx, i) > 0) {
                s.add(i);
            }

        }


        Map.Entry<Integer, Double> temp = null;
        for (Map.Entry<Integer, Double> userRatingEntry : simList) {
            if (userRatingEntry.getKey().equals(userIdj)) {
                temp = userRatingEntry;
                break;
            }
        }
        if (temp == null) {
            return 0.0;
        }
        int countCommon = 0;//用户交集的物品数目
        for (int i = 0; i < 3000; i++) {
            if (trainMatrix.get(userIdj, i) > 0) {
                if (trainMatrix.get(userIdx, i) > 0) {
                    countCommon++;//这里和默认的countCommon有什么区别，有个门槛值
                }
            }
        }
        double sim = 0.0;
        if (countCommon > 0 && s.size() > 0) {
            sim = temp.getValue() * countCommon / s.size();
        }
        return sim;
    }

    public Map<Integer, List<Map.Entry<Integer, Double>>> similarityList = new HashMap<>();

    public void searchDisimilarityList() {
        Table<Integer, Integer, Double> dataTable = HashBasedTable.create();
        Multimap<Integer, Integer> colMap = HashMultimap.create();

        Table<Integer, Integer, Double> dataTable2 = HashBasedTable.create();
        Multimap<Integer, Integer> colMap2 = HashMultimap.create();
        knn = conf.getInt("rec.neighbors.knn.number");
        similarityMatrix = context.getSimilarity().getSimilarityMatrix();
        if (!(null != userSimilarityList && userSimilarityList.length > 0)) {
            createUserSimilarityList();
        }

        // 相似
        for (int userIdx = 0; userIdx < numUsers; userIdx++) {
            int count = 0;

            // 排序可能有问题
            List<Map.Entry<Integer, Double>> simList = userSimilarityList[userIdx];
            List<Map.Entry<Integer, Double>> nns = new ArrayList<>();

            for (Map.Entry<Integer, Double> userRatingEntry : simList) {
                int similarUserIdx = userRatingEntry.getKey();
//            if (!userSet.contains(similarUserIdx)) {
//                continue;
//            }
                double sim = userRatingEntry.getValue();
//                if (sim > 0) {
//                    nns.add(userRatingEntry);
//                    dataTable2.put(userIdx, similarUserIdx, sim);
//                    colMap2.put(similarUserIdx, userIdx);
//                    count++;
//                }
                nns.add(userRatingEntry);
                dataTable2.put(userIdx, similarUserIdx, sim);
                colMap2.put(similarUserIdx, userIdx);
                if (count == knn) {
//                    similarityList.put(userIdx, nns);
                    break;
                }
                count++;

            }


        }


        // 不相似
        for (int userIdx = 0; userIdx < numUsers; userIdx++) {
            int count = 0;

            // 排序可能有问题
            Lists.sortList(userSimilarityList[userIdx]);
            List<Map.Entry<Integer, Double>> simList = userSimilarityList[userIdx];
            List<Map.Entry<Integer, Double>> nns = new ArrayList<>();

            for (Map.Entry<Integer, Double> userRatingEntry : simList) {
                int similarUserIdx = userRatingEntry.getKey();
//            if (!userSet.contains(similarUserIdx)) {
//                continue;
//            }
                double sim = userRatingEntry.getValue();
//                if (sim > 0) {
//                    nns.add(userRatingEntry);
//                    dataTable.put(userIdx, similarUserIdx, sim);
//                    colMap.put(similarUserIdx, userIdx);
//                    count++;
//                }
                nns.add(userRatingEntry);
                dataTable.put(userIdx, similarUserIdx, sim);
                colMap.put(similarUserIdx, userIdx);
                if (count == knn) {
//                    similarityList.put(userIdx, nns);
                    break;
                }
                count++;
            }


        }


        diSimilarityMatrix = new SparseMatrix(numUsers, numUsers, dataTable, colMap);
        impSocialMatrix = new SparseMatrix(numUsers, numUsers, dataTable2, colMap2);

    }

    public void createUserSimilarityList() {
        userSimilarityList = new ArrayList[numUsers];
        for (int userIndex = 0; userIndex < numUsers; ++userIndex) {
            SparseVector similarityVector = similarityMatrix.row(userIndex);
            userSimilarityList[userIndex] = new ArrayList<>(similarityVector.size());
            Iterator<VectorEntry> simItr = similarityVector.iterator();
            while (simItr.hasNext()) {
                VectorEntry simVectorEntry = simItr.next();

                // 可以认为是key和value
                userSimilarityList[userIndex].add(new AbstractMap.SimpleImmutableEntry<>(simVectorEntry.index(), simVectorEntry.get()));
            }
            Lists.sortList(userSimilarityList[userIndex], true);
        }
    }

    @Override
    protected double predict(int userIdx, int itemIdx, boolean bounded) throws LibrecException {
        double predictRating = predict(userIdx, itemIdx);

        if (bounded)
            return denormalize(Maths.logistic(predictRating));

        return predictRating;
    }

    /**
     * denormalize a prediction to the region (minRate, maxRate)
     *
     * @param predictRating a prediction to the region (minRate, maxRate)
     * @return a denormalized prediction to the region (minRate, maxRate)
     */
    protected double denormalize(double predictRating) {
        return minRate + predictRating * (maxRate - minRate);
    }

    /**
     * normalize a rating to the region (0, 1)
     *
     * @param rating a given rating
     * @return a normalized rating
     */
    protected double normalize(double rating) {
        return (rating - minRate) / (maxRate - minRate);
    }
}

