/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2017 Big Stitcher developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.imglib2.algorithm.phasecorrelation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.ComplexType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.BenchmarkHelper;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;
import net.preibisch.stitcher.algorithm.PairwiseStitching;
import net.preibisch.stitcher.algorithm.PairwiseStitchingParameters;



public class PhaseCorrelation2Util {

	/*
	 * copy source to dest. they do not have to be of the same size, but source must fit in dest
	 * @param source
	 * @param dest
	 */
	public static <T extends RealType<T>, S extends RealType<S>> void copyRealImage(final IterableInterval<T> source, final RandomAccessibleInterval<S> dest, final ExecutorService service) {

		final Vector<ImagePortion> portions = FusionTools.divideIntoPortions( source.size() );
		final ArrayList<Future<?>> futures = new ArrayList<>();
		final AtomicInteger ai = new AtomicInteger(-1);

		for (int i = 0; i < portions.size(); i++){
			futures.add(service.submit(new Runnable() {
				@Override
				public void run() {
					final RandomAccess<S> destRA = dest.randomAccess();
					final Cursor<T> srcC = source.localizingCursor();

					final ImagePortion ip = portions.get(ai.incrementAndGet());

					final long loopSize = ip.getLoopSize();

					srcC.jumpFwd(ip.getStartPosition());

					for (long l = 0; l < loopSize; l++){
						srcC.fwd();
						destRA.setPosition(srcC);
						destRA.get().setReal(srcC.get().getRealDouble());
					}

				}
			}));
		}

		for (final Future<?> f : futures){
			try {
				f.get();
			} catch (final InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (final ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}

	/**
	 * calculate the size difference of two Dimensions objects (dim2-dim1)
	 * @param dim1 first Dimensions
	 * @param dim2 second Dimensions
	 * @return int array of difference
	 */
	public static int[] getSizeDifference(final Dimensions dim1, final Dimensions dim2) {
		final int[] diff = new int[dim1.numDimensions()];
		for (int i = 0; i < dim1.numDimensions(); i++){
			diff[i] = (int) (dim2.dimension(i) - dim1.dimension(i));
		}
		return diff;
	}

	/**
	 * calculate the size of an extended image big enough to hold dim1 and dim2
	 * with each dimension also enlarged by extension pixels on each side (but at most by the original image size)
	 * @param dim1 first Dimensions
	 * @param dim2 second Dimensions
	 * @param extension: number of pixels to add at each side in each dimension
	 * @return extended dimensions
	 */
	public static FinalDimensions getExtendedSize(final Dimensions dim1, final Dimensions dim2, final int [] extension) {
		final long[] extDims = new long[dim1.numDimensions()];
		for (int i = 0; i <dim1.numDimensions(); i++){
			extDims[i] = dim1.dimension(i) > dim2.dimension(i) ? dim1.dimension(i) : dim2.dimension(i);
			final long extBothSides = extDims[i] < extension[i] ? extDims[i] * 2 : extension[i] * 2;
			extDims[i] += extBothSides;
		}
		return new FinalDimensions(extDims);
	}

	/*
	 * return a BlendedExtendedMirroredRandomAccesible of img extended extension pixels on each side (but at most by the original image size)
	 * @param img
	 * @param extension: number of blending pixels to add at each side in each dimension
	 * @return
	 */
	public static <T extends RealType<T>> RandomAccessible<T> extendImageByFactor(final RandomAccessibleInterval<T> img, final int [] extension)
	{
		final int[] extEachSide = new int[img.numDimensions()];
		for (int i = 0; i <img.numDimensions(); i++){
			extEachSide[i] = (int) (img.dimension(i) < extension[i] ? img.dimension(i) : extension[i]);
		}
		return new BlendedExtendedMirroredRandomAccesible2<>(img, extEachSide);
	}

	/*
	 * returns the extension at each side if an image is enlarged by a factor of extensionFactor at each side
	 * @param dims
	 * @param extensionFactor
	 * @return
	 */
	public static int[] extensionByFactor(final Dimensions dims, final double extensionFactor){
		final int[] res = new int[dims.numDimensions()];
		for (int i = 0; i< dims.numDimensions(); i++){
			res[i] = (int) (dims.dimension(i)*extensionFactor);
		}
		return res;
	}


	/*
	 * return a BlendedExtendedMirroredRandomAccesible of img extended to extDims
	 * @param img
	 * @param extDims
	 * @return
	 */
	public static <T extends RealType<T>> RandomAccessible<T> extendImageToSize(final RandomAccessibleInterval<T> img, final Dimensions extDims)
	{
		final int[] extEachSide = getSizeDifference(img, extDims);
		for (int i = 0; i< img.numDimensions(); i++){
			extEachSide[i] /= 2;
		}
		return new BlendedExtendedMirroredRandomAccesible2<>(img, extEachSide);
	}


	public static <T extends RealType<T>, S extends RealType<S>> void calculateCrossCorrParallel(
			final List<PhaseCorrelationPeak2> peaks, final RandomAccessibleInterval<T> img1, final RandomAccessibleInterval<S> img2,
			final long minOverlapPx, final ExecutorService service)
	{
		calculateCrossCorrParallel( peaks, img1, img2, minOverlapPx, service, false, null );
	}

	/*
	 * calculate the crosscorrelation of img1 and img2 for all shifts represented by a PhasecorrelationPeak List in parallel using a specified
	 * ExecutorService. service remains functional after the call
	 * @param peaks
	 * @param img1
	 * @param img2
	 * @param minOverlapPx minimal number of overlapping pixels in each Dimension, may be null to indicate no minimum
	 * @param service
	 */
	public static <T extends RealType<T>, S extends RealType<S>> void calculateCrossCorrParallel(
			final List<PhaseCorrelationPeak2> peaks, final RandomAccessibleInterval<T> img1, final RandomAccessibleInterval<S> img2,
			final long minOverlapPx, final ExecutorService service, final boolean interpolateSubpixel,
			final PeakFilter peakFilter)
	{
		final List<Future<?>> futures = new ArrayList<>();

		for (final PhaseCorrelationPeak2 p : peaks){
			futures.add(service.submit(new Runnable() {
				@Override
				public void run() {
					if ( peakFilter == null || peakFilter.testPeak( p.getShift() ) )
					{
						p.calculateCrossCorr(img1, img2, minOverlapPx, interpolateSubpixel);
					}
					else
					{
						// mark the peak invalid because it is outside the peakFilter
						p.setCrossCorr( Double.NEGATIVE_INFINITY );
						p.setnPixel( 0 );
					}
				}
			}));
		}

		for (final Future<?> f: futures){
			try {
				f.get();
			} catch (final InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (final ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	/*
	 * find local maxima in PCM
	 * @param pcm
	 * @param service
	 * @param maxN
	 * @return
	 */
	public static <T extends RealType<T>> List<PhaseCorrelationPeak2> getPCMMaxima(
			final RandomAccessibleInterval<T> pcm,
			final ExecutorService service,
			final int maxN,
			final boolean subpixelAccuracy,
			final PeakFilter peakFilter,
			final Dimensions img1Dims, final Dimensions img2Dims ){

		final List<PhaseCorrelationPeak2> res = new ArrayList<>();

		final ArrayList<Pair<Localizable, Double>> maxima = findPeaksMT(Views.extendPeriodic(pcm), pcm, maxN, service, subpixelAccuracy, peakFilter, img1Dims, img2Dims);

		for (final Pair<Localizable, Double> p: maxima){
			final PhaseCorrelationPeak2 pcp = new PhaseCorrelationPeak2(p.getA(), p.getB());
			if (subpixelAccuracy)
				pcp.calculateSubpixelLocalization(pcm);

			res.add(pcp);
		}
		return res;
	}

	/*
	 * find local maxima in PCM
	 * @param pcm
	 * @param service
	 * @param maxN
	 * @return
	 */
	public static <T extends RealType<T>> List<PhaseCorrelationPeak2> getPCMMaxima(
			final RandomAccessibleInterval<T> pcm,
			final ExecutorService service,
			final int maxN,
			final boolean subpixelAccuracy){

		return getPCMMaxima(pcm, service, maxN, subpixelAccuracy, null, null, null);
	}

	/*
	 * find maxima in PCM, use a temporary thread pool for calculation
	 * @param pcm
	 * @param nMax
	 * @return
	 */
	public static <T extends RealType<T>> List<PhaseCorrelationPeak2> getPCMMaxima(final RandomAccessibleInterval<T> pcm, final int nMax, final boolean subpixelAccuracy){
		final ExecutorService tExecService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		final List<PhaseCorrelationPeak2> res = getPCMMaxima(pcm, tExecService, nMax, subpixelAccuracy);
		tExecService.shutdown();
		return res;
	}


	public static < T extends RealType< T > > ArrayList< Pair< Localizable, Double > > findPeaksMT(
			final RandomAccessible< T > img,
			final Interval region,
			final int maxN,
			final ExecutorService service,
			final boolean subpixelAccuracy,
			final PeakFilter peakFilter,
			final Dimensions img1Dims, final Dimensions img2Dims ){

		final int nTasks = Runtime.getRuntime().availableProcessors() * 4;
		final List<Interval> intervals = FourNeighborhoodExtrema.splitAlongLargestDimension(region, nTasks);
		final List<Future<ArrayList< Pair< Localizable, Double > >>> futures = new ArrayList<>();

		for (final Interval i : intervals){
			futures.add(service.submit(new Callable<ArrayList< Pair< Localizable, Double > >>() {

				@Override
				public ArrayList<Pair<Localizable, Double>> call() throws Exception {
					return findPeaks(img, i, maxN, subpixelAccuracy, peakFilter, region, img1Dims, img2Dims );
				}
			}));
		}

		final List<List< Pair< Localizable, Double > >> toMerge = new ArrayList<>();

		for (final Future<ArrayList< Pair< Localizable, Double > >> f : futures){
			try {
				toMerge.add(f.get());
			} catch (final InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (final ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		final ArrayList< Pair< Localizable, Double > > res = FourNeighborhoodExtrema.merge(toMerge, maxN, new Comparator<Pair< Localizable, Double >>() {

			@Override
			public int compare(final Pair<Localizable, Double> o1, final Pair<Localizable, Double> o2) {
				return (int) Math.signum(o1.getB() - o2.getB());
			}
		});

		return res;
	}

	public static < T extends RealType< T > > ArrayList< Pair< Localizable, Double > > findPeaks(
			final RandomAccessible< T > img,
			final Interval region,
			final int maxN,
			final boolean subpixelAccuracy,
			final PeakFilter peakFilter,
			final Dimensions pcmDims,
			final Dimensions img1Dims, final Dimensions img2Dims)
	{
		final Cursor< T > c = Views.iterable( Views.interval( img, region ) ).localizingCursor();
		final RandomAccess< T > r = img.randomAccess();
		final int n = img.numDimensions();

		final ArrayList< Pair< Localizable, Double > > list = new ArrayList< >();

		for ( int i = 0; i < maxN; ++i )
			list.add( new ValuePair< Localizable, Double >( null, -Double.MAX_VALUE ) );

A:		while ( c.hasNext() )
		{
			final double type = c.next().getRealDouble();
			r.setPosition( c );

			for ( int d = 0; d < n; ++d )
			{
				r.fwd( d );
				if ( type < r.get().getRealDouble() )
					continue A;

				r.bck( d );
				r.bck( d );

				if ( type < r.get().getRealDouble() )
					continue A;

				r.fwd( d );
			}

			if ( peakFilter != null )
			{
				final PhaseCorrelationPeak2 pcp = new PhaseCorrelationPeak2( new Point( c ), type );
				final List<PhaseCorrelationPeak2> possibleShifts = expandPeakToPossibleShifts(pcp, pcmDims, img1Dims, img2Dims);
				boolean acceptPeak = false;
				for ( final PhaseCorrelationPeak2 possibleShift : possibleShifts )
					acceptPeak |= peakFilter.testPeak( possibleShift.getShift() );
				if ( !acceptPeak )
					continue A;
			}

			for ( int i = maxN - 1; i >= 0; --i )
			{
				if ( type < list.get( i ).getB() )
				{
					if ( i == maxN - 1 )
					{
						continue A;
					}
					else
					{
						list.add( i + 1, new ValuePair< Localizable, Double >( new Point( c ), type ) );
						list.remove( maxN );
						continue A;
					}
				}
			}

			list.add( 0, new ValuePair< Localizable, Double >( new Point( c ), type ) );
			list.remove( maxN );
		}

		// remove all null elements
		for ( int i = maxN -1; i >= 0; --i )
			if ( list.get( i ).getA() == null )
				list.remove(  i );

		return list;
	}


	/*
	 * sort PCM Peaks by phaseCorrelation and return a new list containing just the nToKeep highest peaks
	 * @param rawPeaks
	 * @param nToKeep
	 * @return
	 */
	@Deprecated
	public static List<PhaseCorrelationPeak2> getHighestPCMMaxima(final List<PhaseCorrelationPeak2> rawPeaks, final long nToKeep){
		Collections.sort(rawPeaks, Collections.reverseOrder( new PhaseCorrelationPeak2.ComparatorByPhaseCorrelation()));
		final List<PhaseCorrelationPeak2> res = new ArrayList<>();
		for (int i = 0; i < nToKeep; i++){
			res.add(new PhaseCorrelationPeak2(rawPeaks.get(i)));
		}
		return res;
	}
	/*
	 * expand a list of PCM maxima to to a list containing all possible shifts corresponding to these maxima
	 * @param peaks
	 * @param pcmDims
	 * @param img1Dims
	 * @param img2Dims
	 */
	public static void expandPeakListToPossibleShifts(final List<PhaseCorrelationPeak2> peaks,
			final Dimensions pcmDims, final Dimensions img1Dims, final Dimensions img2Dims)
	{
		final List<PhaseCorrelationPeak2> res = new ArrayList<>();
		for (final PhaseCorrelationPeak2 p : peaks){
			res.addAll(expandPeakToPossibleShifts(p, pcmDims, img1Dims, img2Dims));
		}
		peaks.clear();
		peaks.addAll(res);
	}

	/*
	 * expand a single maximum in the PCM to a list of possible shifts corresponding to that peak
	 * an offset due to different images sizes is accounted for
	 * @param peak
	 * @param pcmDims
	 * @param img1Dims
	 * @param img2Dims
	 * @return
	 */
	public static List<PhaseCorrelationPeak2> expandPeakToPossibleShifts(
			final PhaseCorrelationPeak2 peak, final Dimensions pcmDims, final Dimensions img1Dims, final Dimensions img2Dims)
	{
		final int n = pcmDims.numDimensions();
		final double[] subpixelDiff = new double[n];

		if (peak.getSubpixelPcmLocation() != null)
			for (int i = 0; i < n; i++)
				subpixelDiff[i] = peak.getSubpixelPcmLocation().getDoublePosition( i ) - peak.getPcmLocation().getIntPosition( i );

		final int[] originalPCMPeakWithOffset = new int[n];
		peak.getPcmLocation().localize(originalPCMPeakWithOffset);

		final int[] extensionImg1 = getSizeDifference(img1Dims, pcmDims);
		final int[] extensionImg2 = getSizeDifference(img2Dims, pcmDims);
		final int[] offset = new int[pcmDims.numDimensions()];
		for(int i = 0; i < offset.length; i++){
			offset[i] = (extensionImg2[i] - extensionImg1[i] ) / 2;
			originalPCMPeakWithOffset[i] += offset[i];
			originalPCMPeakWithOffset[i] %= pcmDims.dimension(i);
		}

		final List<PhaseCorrelationPeak2> shiftedPeaks = new ArrayList<>();
		for (int i = 0; i < Math.pow(2, pcmDims.numDimensions()); i++){
			final int[] possibleShift = originalPCMPeakWithOffset.clone();
			final PhaseCorrelationPeak2 peakWithShift = new PhaseCorrelationPeak2(peak);
			for (int d = 0; d < pcmDims.numDimensions(); d++){
				/*
				 * mirror the shift around the origin in dimension d if (i / 2^d) is even
				 * --> all possible shifts
				 */
				if ((i / (int) Math.pow(2, d) % 2) == 0){
					possibleShift[d] = possibleShift[d] < 0 ? possibleShift[d] + (int) pcmDims.dimension(d) : possibleShift[d] - (int) pcmDims.dimension(d);
				}
			}
			peakWithShift.setShift(new Point(possibleShift));

			if (peakWithShift.getSubpixelPcmLocation() != null)
			{
				final double[] subpixelShift = new double[n];
				for (int j =0; j<n;j++){
					subpixelShift[j] = possibleShift[j] + subpixelDiff[j];
				}

				peakWithShift.setSubpixelShift( new RealPoint( subpixelShift ) );
			}
			shiftedPeaks.add(peakWithShift);
		}
		return shiftedPeaks;
	}

	/*
	 * get intervals corresponding to overlapping area in two images (relative to image origins)
	 * will return null if there is no overlap
	 * @param img1
	 * @param img2
	 * @param shift
	 * @return
	 */
	public static Pair<Interval, Interval> getOverlapIntervals(final Dimensions img1, final Dimensions img2, final Localizable shift){

		final int numDimensions = img1.numDimensions();
		final long[] offsetImage1 = new long[ numDimensions ];
		final long[] offsetImage2 = new long[ numDimensions ];
		final long[] maxImage1 = new long[ numDimensions ];
		final long[] maxImage2 = new long[ numDimensions ];

		long overlapSize;

		for ( int d = 0; d < numDimensions; ++d )
		{
			if ( shift.getLongPosition(d) >= 0 )
			{
				// two possiblities
				//
				//               shift=start              end
				//                 |					   |
				// A: Image 1 ------------------------------
				//    Image 2      ----------------------------------
				//
				//               shift=start	    end
				//                 |			     |
				// B: Image 1 ------------------------------
				//    Image 2      -------------------

				// they are not overlapping ( this might happen due to fft zeropadding and extension )
				if ( shift.getLongPosition(d) >= img1.dimension( d ) )
				{
					return null;
				}

				offsetImage1[ d ] = shift.getLongPosition(d);
				offsetImage2[ d ] = 0;
				overlapSize = Math.min( img1.dimension( d ) - shift.getLongPosition(d),  img2.dimension( d ) );
				maxImage1[ d ] = offsetImage1[d] + overlapSize -1;
				maxImage2[ d ] = offsetImage2[d] + overlapSize -1;
			}
			else
			{
				// two possiblities
				//
				//          shift start                	  end
				//            |	   |			`		   |
				// A: Image 1      ------------------------------
				//    Image 2 ------------------------------
				//
				//          shift start	     end
				//            |	   |          |
				// B: Image 1      ------------
				//    Image 2 -------------------

				// they are not overlapping ( this might happen due to fft zeropadding and extension
				if ( shift.getLongPosition(d) <= -img2.dimension( d ) )
				{
					return null;
				}

				offsetImage1[ d ] = 0;
				offsetImage2[ d ] = -shift.getLongPosition(d);
				overlapSize =  Math.min( img2.dimension( d ) + shift.getLongPosition(d),  img1.dimension( d ) );
				maxImage1[ d ] = offsetImage1[d] + overlapSize -1;
				maxImage2[ d ] = offsetImage2[d] + overlapSize -1;
			}

		}

		final FinalInterval img1Interval = new FinalInterval(offsetImage1, maxImage1);
		final FinalInterval img2Interval = new FinalInterval(offsetImage2, maxImage2);

		final Pair<Interval, Interval> res = new ValuePair<>(img1Interval, img2Interval);
		return res;
	}

	/*
	 * multiply complex numbers c1 and c2, set res to the result of multiplication
	 * @param c1
	 * @param c2
	 * @param res
	 */
	public static <R extends ComplexType<R>, S extends ComplexType<S>, T extends ComplexType<T>> void multiplyComplex(
			final R c1, final S c2, final T res)
	{
		final double a = c1.getRealDouble();
		final double b = c1.getImaginaryDouble();
		final double c = c2.getRealDouble();
		final double d = c2.getImaginaryDouble();
		res.setReal(a*c - b*d);
		res.setImaginary(a*d + b*c);
	}

	/*
	 * pixel-wise multiplication of img1 and img2
	 * res is overwritten by the result
	 * @param img1
	 * @param img2
	 * @param res
	 */
	public static <R extends ComplexType<R>, S extends ComplexType<S>, T extends ComplexType<T>> void multiplyComplexIntervals(
			final RandomAccessibleInterval<R> img1, final RandomAccessibleInterval<S> img2, final RandomAccessibleInterval<T> res, final ExecutorService service)
	{


		final Vector<ImagePortion> portions = FusionTools.divideIntoPortions (Views.iterable(img1).size() );
		final List<Future<?>> futures = new ArrayList<>();
		final AtomicInteger ai = new AtomicInteger(-1);

		for (int i = 0; i  < portions.size(); i++){
			futures.add(service.submit(new Runnable() {

				@Override
				public void run() {

					final ImagePortion ip = portions.get(ai.incrementAndGet());
					final long loopSize = ip.getLoopSize();

					if (Views.iterable(img1).iterationOrder().equals(Views.iterable(img2).iterationOrder()) &&
							Views.iterable(img1).iterationOrder().equals(Views.iterable(res).iterationOrder())){
						final Cursor<T> cRes = Views.iterable(res).cursor();
						final Cursor<R> cSrc1 = Views.iterable(img1).cursor();
						final Cursor<S> cSrc2 = Views.iterable(img2).cursor();

						cSrc1.jumpFwd(ip.getStartPosition());
						cSrc2.jumpFwd(ip.getStartPosition());
						cRes.jumpFwd(ip.getStartPosition());

						for (long l = 0; l < loopSize; l++){
							cRes.fwd();
							cSrc1.fwd();
							cSrc2.fwd();
							multiplyComplex(cSrc1.get(), cSrc2.get(), cRes.get());
						}
					}

					else {
						final RandomAccess<R> ra1 = img1.randomAccess();
						final RandomAccess<S> ra2 = img2.randomAccess();
						final Cursor<T> cRes = Views.iterable(res).localizingCursor();

						cRes.jumpFwd(ip.getStartPosition());

						for (long l = 0; l < loopSize; l++){
							cRes.fwd();
							ra1.setPosition(cRes);
							ra2.setPosition(cRes);
							multiplyComplex(ra1.get(), ra2.get(), cRes.get());
						}
					}

				}
			}));

			for (final Future<?> f : futures){
				try {
					f.get();
				} catch (final InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (final ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

	}

	/*
	 * calculate complex conjugate of c, save result to res
	 * @param c
	 * @param res
	 */
	public static <R extends ComplexType<R>, S extends ComplexType<S>> void complexConj(final R c, final S res) {
		res.setComplexNumber(c.getRealDouble(), - c.getImaginaryDouble());
	}

	/*
	 * calculate element-wise complex conjugate of img, save result to res
	 * @param img
	 * @param res
	 */
	public static <R extends ComplexType<R>, S extends ComplexType<S>> void complexConjInterval(
			final RandomAccessibleInterval<R>	img, final RandomAccessibleInterval<S> res, final ExecutorService service)
	{

		final Vector<ImagePortion> portions = FusionTools.divideIntoPortions( Views.iterable(img).size() );
		final List<Future<?>> futures = new ArrayList<>();
		final AtomicInteger ai = new AtomicInteger(-1);

		for (int i = 0; i  < portions.size(); i++){
			futures.add(service.submit(new Runnable() {

				@Override
				public void run() {

					final ImagePortion ip = portions.get(ai.incrementAndGet());
					final long loopSize = ip.getLoopSize();

					if (Views.iterable(img).iterationOrder().equals(Views.iterable(res).iterationOrder())){
						final Cursor<S> cRes = Views.iterable(res).cursor();
						final Cursor<R> cSrc = Views.iterable(img).cursor();

						cSrc.jumpFwd(ip.getStartPosition());
						cRes.jumpFwd(ip.getStartPosition());

						for (long l = 0; l < loopSize; l++){
							cRes.fwd();
							cSrc.fwd();
							complexConj(cSrc.get(), cRes.get());
						}
					}

					else {
						final Cursor<S> cRes = Views.iterable(res).localizingCursor();
						final RandomAccess<R> raImg = img.randomAccess();

						cRes.jumpFwd(ip.getStartPosition());

						for (long l = 0; l < loopSize; l++){
							cRes.fwd();
							raImg.setPosition(cRes);
							complexConj(raImg.get(), cRes.get());
						}
					}

				}
			}));

			for (final Future<?> f : futures){
				try {
					f.get();
				} catch (final InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (final ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

	}

	/*
	 * normalize complex number c1 to length 1, save result to res
	 * if the length of c1 is less than normalizationThreshold, set res to 0
	 * @param c1
	 * @param res
	 * @param normalizationThreshold
	 */
	public static <R extends ComplexType<R>, S extends ComplexType<S>> void normalize( final R c1, final S res, final double normalizationThreshold)
	{
		final double len = c1.getPowerDouble();
		if (len > normalizationThreshold){
			res.setReal(c1.getRealDouble()/len);
			res.setImaginary(c1.getImaginaryDouble()/len);
		} else {
			res.setComplexNumber(0, 0);
		}

	}

	/*
	 * normalization with default threshold
	 * @param c1
	 * @param res
	 */
	public static <R extends ComplexType<R>, S extends ComplexType<S>> void normalize( final R c1, final S res){
		normalize(c1, res, 1e-5);
	}


	/*
	 * normalize complex valued img to length 1, pixel-wise, saving result to res
	 * if the length of a pixel is less than normalizationThreshold, set res to 0
	 * @param img
	 * @param res
	 * @param normalizationThreshold
	 */
	public static <T extends ComplexType<T>, S extends ComplexType<S>>void normalizeInterval(
			final RandomAccessibleInterval<T> img, final RandomAccessibleInterval<S> res, final double normalizationThreshold, final ExecutorService service)
	{

		final Vector<ImagePortion> portions = FusionTools.divideIntoPortions( Views.iterable(img).size() );
		final List<Future<?>> futures = new ArrayList<>();
		final AtomicInteger ai = new AtomicInteger(-1);

		for (int i = 0; i  < portions.size(); i++){
			futures.add(service.submit(new Runnable() {

				@Override
				public void run() {

					final ImagePortion ip = portions.get(ai.incrementAndGet());
					final long loopSize = ip.getLoopSize();

					if (Views.iterable(img).iterationOrder().equals(Views.iterable(res).iterationOrder())){
						final Cursor<S> cRes = Views.iterable(res).cursor();
						final Cursor<T> cSrc = Views.iterable(img).cursor();

						cSrc.jumpFwd(ip.getStartPosition());
						cRes.jumpFwd(ip.getStartPosition());

						for (long l = 0; l < loopSize; l++){
							cRes.fwd();
							cSrc.fwd();
							normalize(cSrc.get(), cRes.get(), normalizationThreshold);
						}
					}

					else {
						final Cursor<S> cRes = Views.iterable(res).localizingCursor();
						final RandomAccess<T> raImg = img.randomAccess();

						cRes.jumpFwd(ip.getStartPosition());

						for (long l = 0; l < loopSize; l++){
							cRes.fwd();
							raImg.setPosition(cRes);
							normalize(raImg.get(), cRes.get(), normalizationThreshold);
						}
					}

				}
			}));

			for (final Future<?> f : futures){
				try {
					f.get();
				} catch (final InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (final ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}




	}
	/*
	 * normalization with default threshold
	 * @param img
	 * @param res
	 */
	public static <T extends ComplexType<T>, S extends ComplexType<S>>void normalizeInterval(
			final RandomAccessibleInterval<T> img, final RandomAccessibleInterval<S> res, final ExecutorService service){
		normalizeInterval(img, res, 1E-5, service);
	}

	/*
	 * get the mean pixel intensity of an img
	 * @param img
	 * @return
	 */
	public static <T extends RealType<T>> double getMean(final RandomAccessibleInterval<T> img)
	{
		// TODO: if #pixels > ???? else RealSum
		// TODO: integral image?
		double sum = 0.0;
		long n = 0;
		for (final T pix: Views.iterable(img)){
			sum += pix.getRealDouble();
			n++;
		}
		return sum/n;
	}

	/*
	 * get pixel-value correlation of two RandomAccessibleIntervals
	 * @param img1
	 * @param img2
	 * @return
	 */
	public static <T extends RealType<T>, S extends RealType<S>> double getCorrelation (
			final RandomAccessibleInterval<T> img1, final RandomAccessibleInterval<S> img2)
	{
		final double m1 = getMean(img1);
		final double m2 = getMean(img2);

		// square sums
		double sum11 = 0.0, sum22 = 0.0, sum12 = 0.0;

		final Cursor<T> c1 = Views.iterable(img1).cursor();

		if (Views.iterable( img1 ).iterationOrder().equals( Views.iterable( img2 ).iterationOrder() ))
		{
			final Cursor< S > c2 = Views.iterable( img2 ).cursor();
			while (c1.hasNext()){
				final double c = c1.next().getRealDouble();
				final double r = c2.next().getRealDouble();

				sum11 += (c - m1) * (c - m1);
				sum22 += (r - m2) * (r - m2);
				sum12 += (c - m1) * (r - m2);
			}
		}
		else
		{
			final RandomAccess<S> r2 = img2.randomAccess();
			while (c1.hasNext()){
				final double c = c1.next().getRealDouble();
				r2.setPosition(c1);
				final double r = r2.get().getRealDouble();

				sum11 += (c - m1) * (c - m1);
				sum22 += (r - m2) * (r - m2);
				sum12 += (c - m1) * (r - m2);
			}
		}

		// all pixels had the same color....
		if (sum11 == 0 || sum22 == 0)
		{
			// having the same means and same sums means the overlapping area was simply identically the same color
			// this is most likely an artifact and we return 0
			/* if ( sum11 == sum22 && m1 == m2 )
				return 1;
			else */
				return 0;
		}

		return sum12 / Math.sqrt(sum11 * sum22);
	}


	/*
	 * test stitching, create new image with img2 copied over img1 at the specified shift
	 * @param img1
	 * @param img2
	 * @param shiftPeak
	 * @return
	 */
	public static <T extends RealType<T>, S extends RealType<S>> RandomAccessibleInterval<FloatType> dummyFuse(final RandomAccessibleInterval<T> img1, final RandomAccessibleInterval<S> img2, final PhaseCorrelationPeak2 shiftPeak, final ExecutorService service)
	{
		final long[] shift = new long[img1.numDimensions()];
		shiftPeak.getShift().localize(shift);
		final long[] minImg1 = new long[img1.numDimensions()];
		final long[] minImg2 = new long[img1.numDimensions()];
		final long[] maxImg1 = new long[img1.numDimensions()];
		final long[] maxImg2 = new long[img1.numDimensions()];
		final long[] min = new long[img1.numDimensions()];
		final long[] max = new long[img1.numDimensions()];

		for (int i = 0; i < img1.numDimensions(); i++){
			minImg1[i] = 0;
			maxImg1[i] = img1.dimension(i) -1;
			minImg2[i] = shiftPeak.getShift().getLongPosition(i);
			maxImg2[i] = img2.dimension(i) + minImg2[i] - 1;

			min[i] =  Math.min(minImg1[i], minImg2[i]);
			max[i] = Math.max(maxImg1[i], maxImg2[i]);
		}


		final RandomAccessibleInterval<FloatType> res = new ArrayImgFactory<FloatType>().create(new FinalInterval(min, max), new FloatType());
		copyRealImage(Views.iterable(img1), Views.translate(res, min), service);
		copyRealImage(Views.iterable(Views.translate(img2, shift)), Views.translate(res, min), service);
		return res;

	}

	public static void main( final String[] args )
	{
		final Point p = new Point( 90, 90 ); // identical to (-10,-10), so subpixel localization can move on periodic condition outofbounds
		final PhaseCorrelationPeak2 pcp = new PhaseCorrelationPeak2( p, 5 );

		final Dimensions pcmDims = new FinalDimensions( 100, 100 );
		final Dimensions p1 = new FinalDimensions( 80, 81 );
		final Dimensions p2 = new FinalDimensions( 91, 90 );

		final List<PhaseCorrelationPeak2> peaks = expandPeakToPossibleShifts( pcp, pcmDims, p1, p2 );

		for ( final PhaseCorrelationPeak2 pc : peaks )
			System.out.println( Util.printCoordinates( pc.getShift() ) );

		final Img< FloatType > a = ImgLib2Util.openAs32Bit( new File( "73.tif.zip" ) );
		final Img< FloatType > b = ImgLib2Util.openAs32Bit( new File( "74.tif.zip" ) );

//		BenchmarkHelper.benchmarkAndPrint( 10, true, new Runnable()
//		{
//			@Override
//			public void run()
//			{
//				System.out.println( getCorrelation ( a, b ) );
//			}
//		} );

		BenchmarkHelper.benchmarkAndPrint( 10, true, new Runnable()
		{
			@Override
			public void run()
			{
				PairwiseStitching.getShift( a, b, new Translation3D(), new Translation3D(),
						new PairwiseStitchingParameters(), Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ) );
			}
		} );

//		System.out.println( getCorrelation ( a, b ) );
//		System.out.println( getCorrelation ( a, c ) );
//		System.out.println( getCorrelation ( b, c ) );
	}
}
