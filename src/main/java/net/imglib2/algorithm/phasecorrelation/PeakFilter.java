package net.imglib2.algorithm.phasecorrelation;

import net.imglib2.RealLocalizable;

@FunctionalInterface
public interface PeakFilter
{
	/**
	 * Returns whether the peak at the given position should be accepted or rejected.
	 * Can be called in multiple threads simultaneously without external synchronization.
	 *
	 * @param peakPosition
	 * @return
	 */
	boolean testPeak( final RealLocalizable peakPosition );
}
