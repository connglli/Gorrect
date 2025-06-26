/*
 * MIT License
 *
 * Copyright (c) 2025-2025 Cong Li (cong.li@inf.ethz.ch)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.connglli.gorrect.utility;

import java.util.Random;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * GRandom manages the randomness of Gorrect to enable a reproducible result
 */
public class GRandom {
    private static GRandom instance;
    private final Random random;

    public static GRandom getInstance() {
        if (instance == null) {
            instance = new GRandom();
        }
        return instance;
    }

    public void setSeed(long seed) {
        random.setSeed(seed);
    }

    public void nextBytes(byte[] bytes) {
        random.nextBytes(bytes);
    }

    public int nextInt() {
        return random.nextInt();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public int nextInt(int min, int max) {
        GAssert.set(max > min, "Max bound must be greater than min bound");
        return random.nextInt(max - min) + min;
    }

    public long nextLong() {
        return random.nextLong();
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    public float nextFloat() {
        return random.nextFloat();
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public double nextGaussian() {
        return random.nextGaussian();
    }

    /**
     * Return next index from an array of probabilities. For example, nextIndex([0.1, 0.4, 0.5])
     * returns - 0 with probability 0.1 - 1 with probability 0.4 - 2 with probability 0.5.
     */
    public int nextIndex(double[] probas) {
        GAssert.set(probas.length >= 1, "Input probabilities are not sufficient");
        double[] newProbas = new double[probas.length + 1];
        newProbas[0] = 0f;
        for (int i = 1; i < newProbas.length; i++) {
            newProbas[i] = newProbas[i - 1] + probas[i - 1];
        }
        GAssert.set(Math.abs(1.0f - newProbas[newProbas.length - 1]) <= 1e-5,
                "Input probabilities does not sum to 1.0");
        double x = nextFloat();
        for (int i = 0; i < newProbas.length; i++) {
            if (newProbas[i] < x && x <= newProbas[i + 1]) {
                return i;
            }
        }
        return probas.length - 1;
    }

    public IntStream ints(long streamSize) {
        return random.ints(streamSize);
    }

    public IntStream ints() {
        return random.ints();
    }

    public IntStream ints(long streamSize, int randomNumberOrigin, int randomNumberBound) {
        return random.ints(streamSize, randomNumberOrigin, randomNumberBound);
    }

    public IntStream ints(int randomNumberOrigin, int randomNumberBound) {
        return random.ints(randomNumberOrigin, randomNumberBound);
    }

    public LongStream longs(long streamSize) {
        return random.longs(streamSize);
    }

    public LongStream longs() {
        return random.longs();
    }

    public LongStream longs(long streamSize, long randomNumberOrigin, long randomNumberBound) {
        return random.longs(streamSize, randomNumberOrigin, randomNumberBound);
    }

    public LongStream longs(long randomNumberOrigin, long randomNumberBound) {
        return random.longs(randomNumberOrigin, randomNumberBound);
    }

    public DoubleStream doubles(long streamSize) {
        return random.doubles(streamSize);
    }

    public DoubleStream doubles() {
        return random.doubles();
    }

    public DoubleStream doubles(long streamSize, double randomNumberOrigin,
            double randomNumberBound) {
        return random.doubles(streamSize, randomNumberOrigin, randomNumberBound);
    }

    public DoubleStream doubles(double randomNumberOrigin, double randomNumberBound) {
        return random.doubles(randomNumberOrigin, randomNumberBound);
    }

    private GRandom() {
        random = new Random();
    }
}
