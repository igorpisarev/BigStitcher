package gui.popup;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import algorithm.GroupedViewAggregator;
import algorithm.GroupedViewAggregator.ActionType;
import algorithm.PairwiseStitching;
import algorithm.PairwiseStitchingParameters;
import algorithm.TransformTools;
import algorithm.globalopt.TransformationTools;
import gui.popup.TestPopup.MyActionListener;
import ij.gui.GenericDialog;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.phasecorrelation.PhaseCorrelation2;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import spim.fiji.spimdata.SpimData2;
import spim.fiji.spimdata.boundingbox.BoundingBox;
import spim.fiji.spimdata.explorer.ExplorerWindow;
import spim.fiji.spimdata.explorer.GroupedRowWindow;
import spim.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import spim.process.fusion.boundingbox.overlap.IterativeBoundingBoxDetermination;
import spim.process.fusion.export.DisplayImage;
import spim.process.fusion.transformed.FusedRandomAccessibleInterval;
import spim.process.fusion.transformed.TransformView;
import spim.process.fusion.transformed.TransformVirtual;
import spim.process.fusion.transformed.TransformWeight;
import spim.process.fusion.weightedavg.ProcessFusion;
import spim.process.fusion.weightedavg.ProcessVirtual;

public class DisplayOverlapTestPopup extends JMenuItem implements ExplorerWindowSetable {

	
	ExplorerWindow< ? extends AbstractSpimData< ? extends AbstractSequenceDescription< ?, ?, ? > >, ? > panel;
	
	@Override
	public JComponent setExplorerWindow(
			ExplorerWindow< ? extends AbstractSpimData< ? extends AbstractSequenceDescription< ?, ?, ? > >, ? > panel)
	{
		this.panel = panel;
		return this;
	}
	
	public DisplayOverlapTestPopup()
	{
		super( "Display Overlap Virtually Fused" );
		this.addActionListener( new MyActionListener() );
	}
	
	public class MyActionListener implements ActionListener
	{

		@Override
		public void actionPerformed(ActionEvent e)
		{
			final SpimData spimData = (SpimData)panel.getSpimData();
			
			List<List< ViewId >> views = ((GroupedRowWindow) panel).selectedRowsViewIdGroups();
			
			IterativeBoundingBoxDetermination< ViewId > bbDet = new IterativeBoundingBoxDetermination<>( spimData );
						
			BoundingBox bbOverlap = bbDet.getMaxOverlapBoundingBox( views );			
			System.out.println( "Overlap BB: " + Util.printInterval( bbOverlap ) );
			
			GenericDialog gd = new GenericDialog( "Virtual Fusion" );
			gd.addNumericField( "Downsampling", 1, 0 );
			
			gd.showDialog();

			if ( gd.wasCanceled() )
				return;

			double downsampling =  gd.getNextNumber();
			
			

			//DisplayImage.getImagePlusInstance( new FusedRandomAccessibleInterval( new FinalInterval( dim ), images, weights ), true, "Fused, Virtual", 0, 255 ).show();
			
			List<RandomAccessibleInterval< FloatType >> raiOverlaps = new ArrayList<>();
			
			for (List< ViewId > tileViews : views)
			{
				List<List< ViewId >> wrapped = tileViews.stream().map( v -> {
					ArrayList< ViewId > wrp = new ArrayList<ViewId>();
					wrp.add( v );
					return wrp;} ).collect( Collectors.toList() );
				
				List< RandomAccessibleInterval< FloatType > > openFused = 
						openVirtuallyFused( spimData, wrapped, bbOverlap, new double[]{downsampling,downsampling,downsampling} );
				
				GroupedViewAggregator gva = new GroupedViewAggregator();
				gva.addAction( ActionType.AVERAGE, Channel.class, null );
				gva.addAction( ActionType.PICK_BRIGHTEST, Illumination.class, null );
				
				RandomAccessibleInterval< FloatType > raiI = gva.aggregate( openFused, views.stream().map( v -> v.iterator().next() ).collect( Collectors.toList() ), spimData.getSequenceDescription() );
				raiOverlaps.add(raiI);
			}
			
			for (RandomAccessibleInterval< FloatType >	rai : raiOverlaps)
				ImageJFunctions.show( rai );
			
			RandomAccessibleInterval< FloatType > rai1 = raiOverlaps.get(0);
			RandomAccessibleInterval< FloatType > rai2 = raiOverlaps.get(1);
			
			
			//rai1 = ImageJFunctions.wrapFloat( ImageJFunctions.show( rai1 ).duplicate());
			//rai2 = ImageJFunctions.wrapFloat( ImageJFunctions.show( rai2 ).duplicate());
			
			ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() * 2 );
			
			Pair< double[], Double > shift = PairwiseStitching.getShift( rai1, rai2, 
					new Translation( rai1.numDimensions() ), new Translation( rai1.numDimensions() ),
					PairwiseStitchingParameters.askUserForParameters(), service );
			
			System.out.println( Util.printCoordinates( shift.getA() ) );
			System.out.println( shift.getB() );
			
			for (int d = 0; d < shift.getA().length; d++)
				shift.getA()[d] *= downsampling;
			
			System.out.println( spimData.getViewRegistrations().getViewRegistration( views.get( 1 ).get( 0 ) ).getModel().copy().concatenate( new Translation(shift.getA()) ) );
			
		}
		
		
		
	}
	
	public static double[] inverse(double[] in)
	{
		final double[] res = new double[in.length];
		for (int i = 0; i < in.length; i++)
			res[i] = 1 / in[i];
		return res;
	}

	
	public static <AS extends AbstractSpimData<? extends AbstractSequenceDescription<?,? extends ViewDescription,? extends ImgLoader>>>
		List<RandomAccessibleInterval< FloatType >> openVirtuallyFused(
				AS spimData,
				Collection<? extends Collection<ViewId>> views,
				Interval boundingBox,
				double[] downsamplingFactors)
	{
		final ImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();
		final ViewRegistrations vrs = spimData.getViewRegistrations();
		
		
		final List<RandomAccessibleInterval< FloatType >> openImgs = new ArrayList<>();
		final Interval bbSc = TransformVirtual.scaleBoundingBox( new FinalInterval( boundingBox ), inverse( downsamplingFactors ));
		
		final long[] dim = new long[ bbSc.numDimensions() ];
		bbSc.dimensions( dim );
		
		
		for (Collection<ViewId> viewGroup : views)
		{
			final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
			final ArrayList< RandomAccessibleInterval< FloatType > > weights = new ArrayList<>();
			
			for ( final ViewId viewId : viewGroup )
			{
				final ViewRegistration vr = vrs.getViewRegistration( viewId );
				vr.updateModel();
				AffineTransform3D model = vr.getModel();

				final float[] blending = ProcessFusion.defaultBlendingRange.clone();
				final float[] border = ProcessFusion.defaultBlendingBorder.clone();

				ProcessVirtual.adjustBlending( spimData.getSequenceDescription().getViewDescriptions().get( viewId ), blending, border );

				model = model.copy();
				TransformVirtual.scaleTransform( model, inverse(downsamplingFactors) );


				final RandomAccessibleInterval inputImg = TransformView.openDownsampled( imgLoader, viewId, model );
				
								
				System.out.println( model.inverse() );

				images.add( TransformView.transformView( inputImg, model, bbSc, 0, 1 ) );
				weights.add( TransformWeight.transformBlending( inputImg, border, blending, model, bbSc ) );
			}
			
			openImgs.add( new FusedRandomAccessibleInterval( new FinalInterval( dim ), images, weights ) );
			
		}		
		
		return openImgs;
		
	}
}