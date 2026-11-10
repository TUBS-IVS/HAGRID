/*
 * Licensed to GraphHopper GmbH under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * GraphHopper GmbH licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphhopper.jsprit.core.algorithm.ruin;

import com.graphhopper.jsprit.core.algorithm.ruin.distance.JobDistance;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

/**
 * HAGRID's exact storage/allocation optimization of the pinned jsprit 2.0.0 implementation.
 * Keeps full native capacity, directed distance calls, global maximum and stable tie order.
 * Two-byte job indices halve retained rows for ordinary instances; larger indices use ints.
 * Primitive scratch arrays avoid allocating one ReferencedJob for every ordered pair.
 * The shade filter removes the original class and its nested classes from the application JAR.
 */
class JobNeighborhoodsOptimized implements JobNeighborhoods {
    private final VehicleRoutingProblem vrp;
    private final JobDistance jobDistance;
    private final int capacity;
    private final Job[] jobsByIndex;
    private final char[][] compactNeighbors;
    private final int[][] wideNeighbors;
    private double maxDistance;

    public JobNeighborhoodsOptimized(VehicleRoutingProblem vrp, JobDistance jobDistance, int capacity) {
        this.vrp = vrp;
        this.jobDistance = jobDistance;
        this.capacity = capacity;
        int rowCount = vrp.getJobsInclusiveInitialJobsInRoutes().size() + 1;
        jobsByIndex = new Job[rowCount];
        boolean indicesFitInChar = true;
        for (Job job : vrp.getJobsInclusiveInitialJobsInRoutes().values()) {
            indicesFitInChar &= vrp.getJobIndex(job) <= Character.MAX_VALUE;
        }
        compactNeighbors = indicesFitInChar ? new char[rowCount][] : null;
        wideNeighbors = indicesFitInChar ? null : new int[rowCount][];
        // Native rows are zero-filled before initialise and for jobs without locations.
        // Share that read-only row: initialise replaces rows rather than mutating them.
        if (compactNeighbors != null) {
            Arrays.fill(compactNeighbors, new char[capacity]);
        } else {
            Arrays.fill(wideNeighbors, new int[capacity]);
        }
    }

    @Override
    public void initialise() {
        if (capacity == 0) return;
        Job[] encounterOrder = vrp.getJobsInclusiveInitialJobsInRoutes().values().toArray(Job[]::new);
        double[] distancesByOrdinal = new double[encounterOrder.length];
        int[] sortedOrdinals = new int[encounterOrder.length];
        int[] mergeScratch = new int[encounterOrder.length];
        for (Job source : encounterOrder) {
            if (source.getActivities().get(0).getLocation() == null) continue;
            int sourceIndex = vrp.getJobIndex(source);
            jobsByIndex[sourceIndex] = source;
            int neighborCount = 0;
            for (int ordinal = 0; ordinal < encounterOrder.length; ordinal++) {
                Job target = encounterOrder[ordinal];
                if (target.getActivities().get(0).getLocation() == null || source == target) continue;
                double distance = jobDistance.getDistance(source, target);
                if (distance > maxDistance) maxDistance = distance;
                distancesByOrdinal[ordinal] = distance;
                sortedOrdinals[neighborCount++] = ordinal;
            }
            sortByDistance(sortedOrdinals, mergeScratch, 0, neighborCount, distancesByOrdinal);
            neighborCount = Math.min(neighborCount, capacity);
            if (compactNeighbors != null) {
                char[] row = new char[neighborCount];
                for (int i = 0; i < neighborCount; i++) {
                    row[i] = (char) vrp.getJobIndex(encounterOrder[sortedOrdinals[i]]);
                }
                compactNeighbors[sourceIndex - 1] = row;
            } else {
                int[] row = new int[neighborCount];
                for (int i = 0; i < neighborCount; i++) {
                    row[i] = vrp.getJobIndex(encounterOrder[sortedOrdinals[i]]);
                }
                wideNeighbors[sourceIndex - 1] = row;
            }
        }
    }

    private static int compareOrdinals(int left, int right, double[] distancesByOrdinal) {
        int distanceOrder = Double.compare(distancesByOrdinal[left], distancesByOrdinal[right]);
        return distanceOrder != 0 ? distanceOrder : Integer.compare(left, right);
    }

    private static void sortByDistance(int[] ordinals, int[] scratch, int from, int to,
                                       double[] distancesByOrdinal) {
        if (to - from < 16) {
            for (int i = from + 1; i < to; i++) {
                int ordinal = ordinals[i];
                int previous = i - 1;
                while (previous >= from && compareOrdinals(ordinals[previous], ordinal, distancesByOrdinal) > 0) {
                    ordinals[previous + 1] = ordinals[previous];
                    previous--;
                }
                ordinals[previous + 1] = ordinal;
            }
            return;
        }
        int middle = (from + to) >>> 1;
        sortByDistance(ordinals, scratch, from, middle, distancesByOrdinal);
        sortByDistance(ordinals, scratch, middle, to, distancesByOrdinal);
        if (compareOrdinals(ordinals[middle - 1], ordinals[middle], distancesByOrdinal) <= 0) return;
        System.arraycopy(ordinals, from, scratch, from, to - from);
        int left = from;
        int right = middle;
        for (int i = from; i < to; i++) {
            boolean takeLeft = right == to || (left < middle
                    && compareOrdinals(scratch[left], scratch[right], distancesByOrdinal) <= 0);
            ordinals[i] = takeLeft ? scratch[left++] : scratch[right++];
        }
    }

    @Override
    public Iterator<Job> getNearestNeighborsIterator(int requestedNeighbors, Job source) {
        int sourceIndex = vrp.getJobIndex(source);
        if (sourceIndex <= 0) return Collections.emptyIterator();
        char[] compactRow = compactNeighbors == null ? null : compactNeighbors[sourceIndex - 1];
        int[] wideRow = wideNeighbors == null ? null : wideNeighbors[sourceIndex - 1];
        int rowLength = compactRow == null ? (wideRow == null ? 0 : wideRow.length) : compactRow.length;
        return new Iterator<>() {
            private int position;

            @Override
            public boolean hasNext() {
                return position < requestedNeighbors && position < rowLength;
            }

            @Override
            public Job next() {
                return jobsByIndex[compactRow != null ? compactRow[position++] : wideRow[position++]];
            }
        };
    }

    @Override
    public double getMaxDistance() {
        return maxDistance;
    }

    static class ArrayIterator implements Iterator<Job> {

        private final int noItems;

        private final int[] itemArray;

        private final Job[] jobs;

        private int index = 0;

        public ArrayIterator(int noItems, int[] itemArray, Job[] jobs) {
            this.noItems = noItems;
            this.itemArray = itemArray;
            this.jobs = jobs;
        }

        @Override
        public boolean hasNext() {
            return index < noItems && index < itemArray.length;
        }

        @Override
        public Job next() {
            Job job = jobs[itemArray[index]];
            index++;
            return job;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public String toString() {
        return "[name=neighborhoodWithCapRestriction][capacity=" + capacity + "]";
    }
}
