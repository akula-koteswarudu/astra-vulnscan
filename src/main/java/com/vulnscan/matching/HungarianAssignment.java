package com.vulnscan.matching;

import java.util.Arrays;

/**
 * Maximum-weight one-to-one assignment (Hungarian / Kuhn-Munkres with potentials, O(n^3)).
 * Rectangular matrices are padded with zero weights. Pairs with weight 0 are "not allowed" and are discarded from the
 * result. With weights 1000 + score for judge-approved pairs, the assignment maximises the NUMBER of matches first
 * and their quality second (two matches always beat one higher-scoring match).
 */
public final class HungarianAssignment {

    private HungarianAssignment() {
    }

    /** @return assignment[row] = column, or -1 when the row is unassigned (or only a zero-weight pair was available). */
    public static int[] maximize(double[][] weight) {
        int rows = weight.length;
        int cols = rows == 0 ? 0 : weight[0].length;
        int n = Math.max(rows, cols);
        int[] result = new int[rows];
        Arrays.fill(result, -1);
        if (n == 0) {
            return result;
        }
        double max = 0;
        for (double[] r : weight) {
            for (double w : r) {
                max = Math.max(max, w);
            }
        }
        // Minimise cost = max - weight on an n x n matrix (1-indexed arrays as in the classic formulation).
        double[][] cost = new double[n + 1][n + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                double w = (i <= rows && j <= cols) ? weight[i - 1][j - 1] : 0;
                cost[i][j] = max - w;
            }
        }
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];   // p[j] = row matched to column j
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[n + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                int j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        double cur = cost[i0][j] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        for (int j = 1; j <= n; j++) {
            int i = p[j];
            if (i >= 1 && i <= rows && j <= cols && weight[i - 1][j - 1] > 0) {
                result[i - 1] = j - 1;
            }
        }
        return result;
    }
}
