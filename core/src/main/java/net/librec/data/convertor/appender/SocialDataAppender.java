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
package net.librec.data.convertor.appender;

import com.google.common.collect.*;
import net.librec.conf.Configuration;
import net.librec.conf.Configured;
import net.librec.data.DataAppender;
import net.librec.math.structure.SparseMatrix;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A <tt>SocialDataAppender</tt> is a class to process and store social appender
 * data.
 *
 * @author SunYatong
 */
public class SocialDataAppender extends Configured implements DataAppender {

    /**
     * The size of the buffer
     */
    private static final int BSIZE = 1024 * 1024;

    /**
     * a {@code SparseMatrix} object build by the social data
     */
    private SparseMatrix userSocialMatrix;

    private SparseMatrix userSocialMatrixCopy;

    private SparseMatrix userImpSocialMatrix;

    /**
     * The path of the appender data file
     */
    private String inputDataPath;

    /**
     * User {raw id, inner id} map from rating data
     */
    private BiMap<String, Integer> userIds;

    /**
     * Item {raw id, inner id} map from rating data
     */
    private BiMap<String, Integer> itemIds;

    /**
     * Initializes a newly created {@code SocialDataAppender} object with null.
     */
    public SocialDataAppender() {
        this(null);
    }

    /**
     * Initializes a newly created {@code SocialDataAppender} object with a
     * {@code Configuration} object
     *
     * @param conf {@code Configuration} object for construction
     */
    public SocialDataAppender(Configuration conf) {
        this.conf = conf;
    }

    /**
     * Process appender data.
     *
     * @throws IOException if I/O error occurs during processing
     */
    @Override
    public void processData() throws IOException {
        if (conf != null && StringUtils.isNotBlank(conf.get("data.appender.path"))) {
            inputDataPath = conf.get("dfs.data.dir") + "/" + conf.get("data.appender.path");
            readData(inputDataPath);
        }
    }

    /**
     * Read data from the data file. Note that we didn't take care of the
     * duplicated lines.
     *
     * @param inputDataPath the path of the data file
     * @throws IOException if I/O error occurs during reading
     */
    private void readData(String inputDataPath) throws IOException {

        double[] global = new double[1508];
        int[] hash = new int[1508];//保存每个用户被信任的次数
        // Table {row-id, col-id, rate}
        Table<Integer, Integer, Double> dataTable = HashBasedTable.create();
        // Map {col-id, multiple row-id}: used to fast build a rating matrix
        Multimap<Integer, Integer> colMap = HashMultimap.create();

        Table<Integer, Integer, Double> dataTable1 = HashBasedTable.create();
        // Map {col-id, multiple row-id}: used to fast build a rating matrix
        Multimap<Integer, Integer> colMap1 = HashMultimap.create();
        Table<Integer, Integer, Double> dataTable2 = HashBasedTable.create();
        // Map {col-id, multiple row-id}: used to fast build a rating matrix
        Multimap<Integer, Integer> colMap2 = HashMultimap.create();
        // BiMap {raw id, inner id} userIds, itemIds
        final List<File> files = new ArrayList<File>();
        final ArrayList<Long> fileSizeList = new ArrayList<Long>();
        SimpleFileVisitor<Path> finder = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                fileSizeList.add(file.toFile().length());
                files.add(file.toFile());
                return super.visitFile(file, attrs);
            }
        };
        Files.walkFileTree(Paths.get(inputDataPath), finder);
        long allFileSize = 0;
        for (Long everyFileSize : fileSizeList) {
            allFileSize = allFileSize + everyFileSize.longValue();
        }
        // loop every dataFile collecting from walkFileTree
        for (File dataFile : files) {
            FileInputStream fis = new FileInputStream(dataFile);
            FileChannel fileRead = fis.getChannel();
            ByteBuffer buffer = ByteBuffer.allocate(BSIZE);
            int len;
            String bufferLine = new String();
            byte[] bytes = new byte[BSIZE];
            while ((len = fileRead.read(buffer)) != -1) {
                buffer.flip();
                buffer.get(bytes, 0, len);
                bufferLine = bufferLine.concat(new String(bytes, 0, len)).replaceAll("\r", "\n");
                String[] bufferData = bufferLine.split("(\n)+");
                boolean isComplete = bufferLine.endsWith("\n");
                int loopLength = isComplete ? bufferData.length : bufferData.length - 1;
                for (int i = 0; i < loopLength; i++) {
                    String line = new String(bufferData[i]);
                    String[] data = line.trim().split("[ \t,]+");
                    String userA = data[0];
                    String userB = data[1];
                    Double rate = (data.length >= 3) ? Double.valueOf(data[2]) : 1.0;
                    if (userIds.containsKey(userA) && userIds.containsKey(userB)) {
                        int row = userIds.get(userA);
                        int col = userIds.get(userB);
                        hash[col]++;
                        dataTable.put(row, col, rate);
                        colMap.put(col, row);
                        dataTable1.put(row, col, rate);
                        colMap1.put(col, row);
                    }
                }
                if (!isComplete) {
                    bufferLine = bufferData[bufferData.length - 1];
                }
                buffer.clear();
            }
            fileRead.close();
            fis.close();
        }
        int numRows = userIds.size(), numCols = userIds.size();
        Arrays.sort(hash);
        int MMax = -1;
        int MMin = 2000;
        double MIMI = -1.0;
        double NINI = 2000.0;
//        for (int i = hash.length - 1; i >= 0; i--) {
//            if (hash[i] > 0) {
//                if (hash[i] > MMax) MMax = hash[i];
//                if (hash[i] < MMin) MMin = hash[i];
//            }
//        }
//        double dd = Math.log10(MMax) - Math.log10(MMin);
//        double dd = 39 - MMin;
        int countHash = 0;
//        for (int i = 0; i < 1508; i++) {
//            if (hash[i] > 0) {
//                countHash++;
////                global[i] = (Math.log10(hash[i]) - Math.log10(MMin)) / dd;
//                global[i] = ((hash[i]) - (MMin)) / dd * Math.log10(1508 / hash[i]);
//                System.out.println(i + " " + hash[i] + " " + global[i]);
////                System.out.println(global[i]);
//
//            }
//        }
        // build rating matrix
        userSocialMatrix = new SparseMatrix(numRows, numCols, dataTable, colMap);

        int h1 = 0;
        int h2 = 0;
        for (int i1 = 0; i1 < 1508; i1++) {
            for (int k = 0; k < 1508; k++) {
                if (i1 != k && !userSocialMatrix.contains(i1, k)) {
                    int countNumber = 0;
                    for (int t = 0; t < 1508; t++) {
                        if (i1 != k && k != t && i1 != t && userSocialMatrix.contains(i1, t) && userSocialMatrix.contains(t, k)
                                && userSocialMatrix.get(i1, t) > 0 && userSocialMatrix.get(t, k) > 0) {
                            if (userSocialMatrix.get(i1, t) > 0 && userSocialMatrix.get(t, k) > 0) {
                                countNumber++;
                            }
//
//                            dataTable1.put(i, k, 0.7);
//                            colMap1.put(k, i);
//
//                            break;
                        }
                    }
                    if (countNumber == 1) {
                        h1++;
//                        dataTable1.put(i, k, 0.7);
//                        colMap1.put(k, i);
                    }
                    if (countNumber >= 2) {
                        h2++;
                        // TODO 这里需要改回来
                        dataTable1.put(i1, k, 0.667);
                        colMap1.put(k, i1);
//                        dataTable2.put(i1, k, 0.7);
//                        colMap2.put(k, i1);
                    }
                }
            }

        }

        userSocialMatrixCopy = new SparseMatrix(numRows, numCols, dataTable1, colMap1);

        for (int i = 0; i < 1508; i++) {
            int trustee = userSocialMatrixCopy.columnSize(i);

            if (trustee > 0) {
                if (trustee > MMax) MMax = trustee;
                if (trustee < MMin) MMin = trustee;
            }
        }
        for (int i = 0; i < 1508; i++) {
            int trustee = userSocialMatrixCopy.columnSize(i);
            if (trustee > 0) {
                countHash++;
//                global[i] = (Math.log10(hash[i]) - Math.log10(MMin)) / dd;
                global[i] = (trustee - (MMin)) / (MMax - MMin) * Math.log10(1508 / trustee);
                global[i] = Math.log(trustee / MMin) / Math.log(MMax / MMin);
                System.out.println(i + " " + trustee + " " + global[i]);
//                System.out.println(global[i]);

            }
        }

        double[][] p1 = new double[1508][1508];
        double[][] prefixp1 = new double[1508][1508];

        double[][] p2 = new double[1508][1508];
        double[][] prefixp2 = new double[1508][1508];

//        double[][] twoLayer = new double[1508][1508];
//        double[][] prefixTwoLayer = new double[1508][1508];
//        double[][] threeLayer = new double[1508][1508];
//        double[][] prefixThreeLayer = new double[1508][1508];
//
//        for (int userIdx = 0; userIdx < 1508; userIdx++) {
//            for (int userIdj = 0; userIdj < 1508; userIdj++) {
//                for (int propagationTwoLayerId = 0; propagationTwoLayerId < 1508; propagationTwoLayerId++) {
//                    if (userSocialMatrixCopy.contains(userIdx, propagationTwoLayerId)
//                            && userSocialMatrixCopy.contains(propagationTwoLayerId, userIdj)) {
//                        prefixTwoLayer[userIdx][userIdj] += userSocialMatrixCopy.get(userIdx, propagationTwoLayerId);
//                        twoLayer[userIdx][userIdj] += userSocialMatrixCopy.get(userIdx, propagationTwoLayerId)
//                                + userSocialMatrixCopy.get(propagationTwoLayerId, userIdj);
//
//
//                    }
//                    for (int propagationThreeLayerId = 0; propagationThreeLayerId < 1508; propagationThreeLayerId++) {
//                        if (userSocialMatrixCopy.contains(userIdx, propagationTwoLayerId)
//                                && userSocialMatrixCopy.contains(propagationTwoLayerId, propagationThreeLayerId)
//                                && userSocialMatrixCopy.contains(propagationThreeLayerId, userIdj)) {
//                            prefixThreeLayer[userIdx][userIdj] += userSocialMatrixCopy.get(userIdx, propagationTwoLayerId)
//                                + userSocialMatrixCopy.get(propagationTwoLayerId, propagationThreeLayerId);
//                            threeLayer[userIdx][userIdj] += userSocialMatrixCopy.get(userIdx, propagationTwoLayerId)
//                                    + userSocialMatrixCopy.get(propagationTwoLayerId, propagationThreeLayerId)
//                                    + userSocialMatrixCopy.get(propagationThreeLayerId, userIdj);
//
//                        }
//                    }
//
//                }
//            }
//        }
//
//        for (int userIdx = 0; userIdx < 1508; userIdx++) {
//            for (int userIdj = 0; userIdj < 1508; userIdj++) {
//                if (twoLayer[userIdx][userIdj] > 0 || threeLayer[userIdx][userIdj] > 0) {
//                    double answerSum = twoLayer[userIdx][userIdj] * 0.667 + threeLayer[userIdx][userIdj] * 0.333;
//                    double prefix = prefixTwoLayer[userIdx][userIdj] + threeLayer[userIdx][userIdj];
//                    if (prefix > 0) {
//                        dataTable2.put(userIdx, userIdj, answerSum / prefix * 1.0);
//                        colMap2.put(userIdj, userIdx);
//                    }
//                }
//            }
//        }

        for (int i2 = 0; i2 < 1508; i2++) {
            for (int k = 0; k < 1508; k++) {
                if (i2 != k && userSocialMatrixCopy.contains(i2, k) && userSocialMatrixCopy.get(i2, k) > 0) {
                    for (int k1 = 0; k1 < 1508; k1++) {
                        if (k1 != k && k1 != i2 && userSocialMatrixCopy.contains(k, k1)
                                && userSocialMatrixCopy.get(k, k1) > 0) {
                            prefixp1[i2][k1] += userSocialMatrixCopy.get(i2, k);
                            p1[i2][k1] += userSocialMatrixCopy.get(i2, k) * userSocialMatrixCopy.get(k, k1);
                            for (int k2 = 0; k2 < 1508; k2++) {
                                if (k2 != k1 && k2 != k && k2 != i2 &&
                                        userSocialMatrixCopy.contains(k1, k2) && userSocialMatrixCopy.get(k1, k2) > 0) {
                                    prefixp2[i2][k2] += userSocialMatrixCopy.get(i2, k) * userSocialMatrixCopy.get(k, k1);
                                    p2[i2][k2] += userSocialMatrixCopy.get(i2, k) * userSocialMatrixCopy.get(k, k1)
                                            * userSocialMatrixCopy.get(k1, k2);
                                }
                            }
                        }
                    }

                }

            }

        }
        double globalWeight = 1.0; // 这里是1的确是正确的，但是不合理；
        for (int sA = 0; sA < 1508; sA++) {
            for (int sB = 0; sB < 1508; sB++) {
                if ((p1[sA][sB] > 0 || p2[sA][sB] > 0) && !userSocialMatrixCopy.contains(sA, sB)) {
                    double answer = p1[sA][sB] * 0.667 + p2[sA][sB] * 0.333;
                    double prefix = prefixp1[sA][sB] + prefixp2[sA][sB];
                    if (prefix > 0 && global[sB] > 0) {
                        double localTrust = answer / prefix * 1.0;
                        double temp = globalWeight * global[sB] + (1 - globalWeight) * localTrust;
                        dataTable2.put(sA, sB, temp);
                        colMap2.put(sB, sA);
                    }
                }
//                double sumCB = 0.0;
//                int countAB = 0;
//                double tempAB = 0.0;
//                if (p1[sA][sB] > 0 && !userSocialMatrixCopy.contains(sA, sB)) {
//
//                    for (int sC = 0; sC < 1508; sC++) {
//                        if (userSocialMatrixCopy.contains(sC, sB) && userSocialMatrixCopy.get(sC, sB) > 0) {
//                            if (p1[sA][sC] > 0 && !userSocialMatrixCopy.contains(sA, sC)) {
//                                sumCB += p1[sA][sC];
//                            }
//                            if (userSocialMatrixCopy.contains(sA, sC) && userSocialMatrixCopy.get(sA, sC) > 0) {
//                                countAB++;
//                                sumCB += userSocialMatrixCopy.get(sA, sC);
//                                tempAB = userSocialMatrixCopy.get(sA, sC);
//                            }
//                        }
//                    }
//
//                    double temp = 0.0;
//                    if (sumCB > 0) {
//                        temp = p1[sA][sB] / sumCB * 1.0;
//                    }
////                    if (countAB == 1) {
////                        temp = temp * tempAB;
////                    }
////                    double answer = 0;
////                    if (global[sB] > 0) {
////                        answer = globalWeight * global[sB] + (1 - globalWeight) * temp;
////                    }
//                    if (temp > 0) {
//                        dataTable2.put(sA, sB, temp);
//                        colMap2.put(sB, sA);
//                    }
//
//                }

            }
        }
        userImpSocialMatrix = new SparseMatrix(numRows, numCols, dataTable2, colMap2);
        // release memory of data table
        dataTable = null;
        dataTable2 = null;
    }

    /**
     * Get user appender.
     *
     * @return the {@code SparseMatrix} object built by the social data.
     */
    public SparseMatrix getUserAppender() {
        return userSocialMatrix;
    }

    public SparseMatrix getImpUserAppender() {
        return userImpSocialMatrix;
    }

    /**
     * Get item appender.
     *
     * @return null
     */
    public SparseMatrix getItemAppender() {
        return null;
    }

    /**
     * Set user mapping data.
     *
     * @param userMappingData user {raw id, inner id} map
     */
    @Override
    public void setUserMappingData(BiMap<String, Integer> userMappingData) {
        this.userIds = userMappingData;
    }

    /**
     * Set item mapping data.
     *
     * @param itemMappingData item {raw id, inner id} map
     */
    @Override
    public void setItemMappingData(BiMap<String, Integer> itemMappingData) {
        this.itemIds = itemMappingData;
    }
}
