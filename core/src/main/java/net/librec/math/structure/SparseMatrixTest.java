package net.librec.math.structure;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;

/**
 * @author szkb
 * @date 2018/12/12 10:33
 */
public class SparseMatrixTest {

    public static void main(String[] args) {
        SparseMatrix matrix;

        Table<Integer, Integer, Double> dataTable = HashBasedTable.create();
        Multimap<Integer, Integer> colMap = HashMultimap.create();

        dataTable.put(1, 1, 0.5);
        colMap.put(1, 1);

        dataTable.put(1, 2, 0.3);
        colMap.put(2, 1);

        dataTable.put(0, 3, 1.0);
        colMap.put(3, 0);

        dataTable.put(2, 3, 1.0);
        colMap.put(3, 2);

        matrix = new SparseMatrix(4, 4, dataTable, colMap);

        System.out.println(matrix);

        System.out.println(matrix.column(3).get(2));

    }
}
