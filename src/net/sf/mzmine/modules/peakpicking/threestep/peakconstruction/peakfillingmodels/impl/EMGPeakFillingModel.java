/*
 * Copyright 2006-2008 The MZmine Development Team
 * 
 * This file is part of MZmine.
 * 
 * MZmine is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.peakpicking.threestep.peakconstruction.peakfillingmodels.impl;

import net.sf.mzmine.data.ChromatographicPeak;
import net.sf.mzmine.data.RawDataFile;
import net.sf.mzmine.data.Scan;
import net.sf.mzmine.data.impl.SimpleDataPoint;
import net.sf.mzmine.data.impl.SimpleMzPeak;
import net.sf.mzmine.modules.peakpicking.threestep.peakconstruction.ConnectedPeak;
import net.sf.mzmine.modules.peakpicking.threestep.peakconstruction.peakfillingmodels.PeakFillingModel;
import net.sf.mzmine.modules.peakpicking.threestep.xicconstruction.ConnectedMzPeak;
import net.sf.mzmine.util.Range;

public class EMGPeakFillingModel implements PeakFillingModel {

    private float xRight = -1, xLeft = -1;

    /**
     * This method return a peak with a the most closest EMG shape to the
     * original peak.
     * 
     * @param originalDetectedPeak
     * @param params
     * 
     * @return peak
     */
    public ChromatographicPeak fillingPeak(
            ChromatographicPeak originalDetectedShape, float[] params) {

        xRight = -1;
        xLeft = -1;

        ConnectedMzPeak[] listMzPeaks = ((ConnectedPeak) originalDetectedShape).getAllMzPeaks();

        RawDataFile dataFile = originalDetectedShape.getDataFile();
        Range originalRange = originalDetectedShape.getRawDataPointsRTRange();
        float rangeSize = originalRange.getSize();
        float mass = originalDetectedShape.getMZ();
        Range extendedRange = new Range(originalRange.getMin() - rangeSize,
                originalRange.getMax() + rangeSize);
        int[] listScans = dataFile.getScanNumbers(1, extendedRange);

        float heightMax, RT, C, FWHM, factor;

        C = params[0]; // level of excess;
        factor = (1.0f - (Math.abs(C) / 10.0f));

        heightMax = originalDetectedShape.getHeight() * factor;
        RT = originalDetectedShape.getRT();

        FWHM = calculateWidth(listMzPeaks, heightMax, RT) * factor;

        if (FWHM < 0) {
            return originalDetectedShape;
        }

        /*
         * Calculates asymmetry of the peak using the formula
         * 
         * Ap = FWHM / ([1.76 (b/a)^2] - [11.15 (b/a)] + 28)
         * 
         * This formula is a variation of Foley J.P., Dorsey J.G. "Equations for
         * calculation of chormatographic figures of Merit for Ideal and Skewed
         * Peaks", Anal. Chem. 1983, 55, 730-737.
         */

        // Left side
        float beginning = listMzPeaks[0].getScan().getRetentionTime();
        float a = (RT - (beginning * factor));

        // Right side
        float ending = listMzPeaks[listMzPeaks.length - 1].getScan().getRetentionTime();
        float b = ((ending * factor) - RT);

        float paramA = b / a;
        float paramB = (1.76f * (float) Math.pow(paramA, 2))
                - (11.15f * paramA) + 28.0f;

        // Calculates Width at base of the peak.
        float paramC = (FWHM * 2.355f) / 4.71f;

        float Ap = paramC / paramB;

        if ((b > a) && (Ap > 0))
            Ap *= -1;

        // Calculate intensity of each point in the shape.
        float t, shapeHeight;
        Scan scan;

        ConnectedMzPeak newMzPeak = null;
        ConnectedPeak filledPeak = null;

        for (int scanIndex : listScans) {

            scan = dataFile.getScan(scanIndex);
            t = scan.getRetentionTime();
            shapeHeight = calculateEMGIntensity(heightMax, RT, FWHM, Ap, C, t);

            // Level of significance
            if (shapeHeight < (heightMax * 0.001))
                continue;

            newMzPeak = getDetectedMzPeak(listMzPeaks, scan);

            if (newMzPeak != null) {
                ((SimpleMzPeak) newMzPeak.getMzPeak()).setIntensity(shapeHeight);
            } else {
                newMzPeak = new ConnectedMzPeak(scan, new SimpleMzPeak(
                        new SimpleDataPoint(mass, shapeHeight)));
            }

            if (filledPeak == null) {
                filledPeak = new ConnectedPeak(
                        originalDetectedShape.getDataFile(), newMzPeak);
            }

            filledPeak.addMzPeak(newMzPeak);
        }

        return filledPeak;
    }

    /**
     * This method calculates the width of the chromatographic peak at half
     * intensity
     * 
     * @param listMzPeaks
     * @param height
     * @param RT
     * @return FWHM
     */
    private float calculateWidth(ConnectedMzPeak[] listMzPeaks, float height,
            float RT) {

        float halfIntensity = height / 2, intensity = 0, intensityPlus = 0, retentionTime = 0;
        ConnectedMzPeak[] rangeDataPoints = listMzPeaks; // .clone();

        for (int i = 0; i < rangeDataPoints.length - 1; i++) {

            intensity = rangeDataPoints[i].getMzPeak().getIntensity();
            intensityPlus = rangeDataPoints[i + 1].getMzPeak().getIntensity();
            retentionTime = rangeDataPoints[i].getScan().getRetentionTime();

            if (intensity > height)
                continue;

            // Left side of the curve
            if (retentionTime < RT) {
                if ((intensity <= halfIntensity) && (retentionTime < RT)
                        && (intensityPlus >= halfIntensity)) {

                    // First point with intensity just less than half of total
                    // intensity
                    float leftY1 = intensity;
                    float leftX1 = retentionTime;

                    // Second point with intensity just bigger than half of
                    // total
                    // intensity
                    float leftY2 = intensityPlus;
                    float leftX2 = rangeDataPoints[i + 1].getScan().getRetentionTime();

                    // We calculate the slope with formula m = Y1 - Y2 / X1 - X2
                    float mLeft = (leftY1 - leftY2) / (leftX1 - leftX2);

                    // We calculate the desired point (at half intensity) with
                    // the
                    // linear equation
                    // X = X1 + [(Y - Y1) / m ], where Y = half of total
                    // intensity
                    xLeft = leftX1 + (((halfIntensity) - leftY1) / mLeft);
                    continue;
                }
            }

            // Right side of the curve
            if (retentionTime > RT) {
                if ((intensity >= halfIntensity) && (retentionTime > RT)
                        && (intensityPlus <= halfIntensity)) {

                    // First point with intensity just bigger than half of total
                    // intensity
                    float rightY1 = intensity;
                    float rightX1 = retentionTime;

                    // Second point with intensity just less than half of total
                    // intensity
                    float rightY2 = intensityPlus;
                    float rightX2 = rangeDataPoints[i + 1].getScan().getRetentionTime();

                    // We calculate the slope with formula m = Y1 - Y2 / X1 - X2
                    float mRight = (rightY1 - rightY2) / (rightX1 - rightX2);

                    // We calculate the desired point (at half intensity) with
                    // the
                    // linear equation
                    // X = X1 + [(Y - Y1) / m ], where Y = half of total
                    // intensity
                    xRight = rightX1 + (((halfIntensity) - rightY1) / mRight);
                    break;
                }
            }
        }

        if ((xRight <= -1) && (xLeft > 0)) {
            float beginning = rangeDataPoints[0].getScan().getRetentionTime();
            float ending = rangeDataPoints[rangeDataPoints.length - 1].getScan().getRetentionTime();
            xRight = RT + (ending - beginning) / 4.71f;
        }

        if ((xRight > 0) && (xLeft <= -1)) {
            float beginning = rangeDataPoints[0].getScan().getRetentionTime();
            float ending = rangeDataPoints[rangeDataPoints.length - 1].getScan().getRetentionTime();
            xLeft = RT - (ending - beginning) / 4.71f;
        }

        boolean negative = (((xRight - xLeft)) < 0);

        if ((negative) || ((xRight == -1) && (xLeft == -1))) {
            float beginning = rangeDataPoints[0].getScan().getRetentionTime();
            float ending = rangeDataPoints[rangeDataPoints.length - 1].getScan().getRetentionTime();
            xRight = RT + (ending - beginning) / 9.42f;
            xLeft = RT - (ending - beginning) / 9.42f;
        }

        // 
        float FWHM = (xRight - xLeft) / 2.355f;

        return FWHM;
    }

    /**
     * 
     * This method calculates the height of Exponential Modified Gaussian
     * function, using the mathematical model proposed by Zs. P�pai and T. L.
     * Pap "Determination of chromatographic peak parameters by non-linear curve
     * fitting using statistical moments", The Analyst,
     * http://www.rsc.org/publishing/journals/AN/article.asp?doi=b111304f
     * 
     * @param heightMax
     * @param RT
     * @param Dp
     * @param Ap
     * @param C
     * @param t
     * @return intensity
     */
    private float calculateEMGIntensity(float heightMax, float RT, float Dp,
            float Ap, float C, float t) {
        float shapeHeight;

        double partA1 = Math.pow((t - RT), 2) / (2 * Math.pow(Dp, 2));
        double partA = heightMax * Math.exp(-1 * partA1);

        double partB1 = (t - RT) / Dp;
        double partB2 = (Ap / 6.0f) * (Math.pow(partB1, 2) - (3 * partB1));
        double partB3 = (C / 24)
                * (Math.pow(partB1, 4) - (6 * Math.pow(partB1, 2)) + 3);
        double partB = 1 + partB2 + partB3;

        shapeHeight = (float) (partA * partB);

        if (shapeHeight < 0)
            shapeHeight = 0;

        return shapeHeight;
    }

    public ConnectedMzPeak getDetectedMzPeak(ConnectedMzPeak[] listMzPeaks,
            Scan scan) {
        int scanNumber = scan.getScanNumber();
        for (int i = 0; i < listMzPeaks.length; i++) {
            if (listMzPeaks[i].getScan().getScanNumber() == scanNumber)
                return listMzPeaks[i].clone();
        }
        return null;
    }

}