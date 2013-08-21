package net.vhati.modmanager.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.vhati.ftldat.FTLDat;
import net.vhati.ftldat.FTLDat.AbstractPack;
import net.vhati.ftldat.FTLDat.FTLPack;
import net.vhati.modmanager.core.ModPatchObserver;
import net.vhati.modmanager.core.ModUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class ModPatchThread extends Thread {

	private static final Logger log = LogManager.getLogger(ModPatchThread.class);

	// Other threads can check or set this.
	public volatile boolean keepRunning = true;

	private Thread shutdownHook = null;

	private List<File> modFiles = new ArrayList<File>();
	private BackedUpDat dataDat = null;
	private BackedUpDat resDat = null;
	private ModPatchObserver observer = null;

	private final int progMax = 100;
	private final int progBackupMax = 25;
	private final int progClobberMax = 25;
	private final int progModsMax = 40;
	private final int progRepackMax = 5;
	private int progMilestone = 0;

	public ModPatchThread( List<File> modFiles, BackedUpDat dataDat, BackedUpDat resDat, ModPatchObserver observer ) {
		this.modFiles.addAll( modFiles );
		this.dataDat = dataDat;
		this.resDat = resDat;
		this.observer = observer;
	}


	public void run() {
		boolean result;
		Exception exception = null;

		// When JVM tries to exit, stall until this thread ends on its own.
		shutdownHook = new Thread() {
			@Override
			public void run() {
				keepRunning = false;
				boolean interrupted = false;
				try {
					while ( true ) {
						try {
							ModPatchThread.this.join();
							break;
						}
						catch ( InterruptedException e ) {
							interrupted = true;
						}
					}
				}
				finally {
					if ( interrupted ) Thread.currentThread().interrupt();
				}
			}
		};
		Runtime.getRuntime().addShutdownHook( shutdownHook );

		try {
			result = patch();
		}
		catch ( Exception e ) {
			log.error( "Patching failed. See log for details.", e );
			exception = e;
			result = false;
		}

		observer.patchingEnded( result, exception );

		Runtime.getRuntime().removeShutdownHook( shutdownHook );
	}


	private boolean patch() throws IOException {

		observer.patchingProgress( 0, progMax );

		BackedUpDat[] allDats = new BackedUpDat[] {dataDat, resDat};

		FTLPack dataP = null;
		FTLPack resP = null;

		try {
			int backupsCreated = 0;
			int datsClobbered = 0;
			int modsInstalled = 0;
			int datsRepacked = 0;

			// Create backup dats, if necessary.
			for ( BackedUpDat dat : allDats ) {
				if ( !dat.bakFile.exists() ) {
					log.info( String.format( "Backing up \"%s\".", dat.datFile.getName() ) );
					observer.patchingStatus( String.format( "Backing up \"%s\".", dat.datFile.getName() ) );

					FTLDat.copyFile( dat.datFile, dat.bakFile );
					backupsCreated++;
					observer.patchingProgress( progMilestone + progBackupMax/allDats.length*backupsCreated, progMax );

					if ( !keepRunning ) return false;
				}
			}
			progMilestone += progBackupMax;
			observer.patchingProgress( progMilestone, progMax );
			observer.patchingStatus( null );

			if ( backupsCreated != allDats.length ) {
				// Clobber current dat files with their respective backups.
				// But don't bother if we made those backups just now.

				for ( BackedUpDat dat : allDats ) {
					log.info( String.format( "Restoring vanilla \"%s\"...", dat.datFile.getName() ) );
					observer.patchingStatus( String.format( "Restoring vanilla \"%s\"...", dat.datFile.getName() ) );

					FTLDat.copyFile( dat.bakFile, dat.datFile );
					datsClobbered++;
					observer.patchingProgress( progMilestone + progClobberMax/allDats.length*datsClobbered, progMax );

					if ( !keepRunning ) return false;
				}
				observer.patchingStatus( null );
			}
			progMilestone += progClobberMax;
			observer.patchingProgress( progMilestone, progMax );

			if ( modFiles.isEmpty() ) {
				// No mods. Nothing else to do.
				observer.patchingProgress( progMax, progMax );
				return true;
			}

			dataP = new FTLPack( dataDat.datFile, false );
			resP = new FTLPack( resDat.datFile, false );

			Map<String,AbstractPack> topFolderMap = new HashMap<String,AbstractPack>();
			topFolderMap.put( "data", dataP );
			topFolderMap.put( "audio", resP );
			topFolderMap.put( "fonts", resP );
			topFolderMap.put( "img", resP );

			// Track modified innerPaths in case they're clobbered.
			List<String> moddedItems = new ArrayList<String>();

			List<String> knownPaths = new ArrayList<String>();
			knownPaths.addAll( dataP.list() );
			knownPaths.addAll( resP.list() );

			List<String> knownPathsLower = new ArrayList<String>( knownPaths.size() );
			for ( String innerPath : knownPaths ) {
				knownPathsLower.add( innerPath.toLowerCase() );
			}

			// Group1: parentPath, Group2: topFolder, Group3: fileName
			Pattern pathPtn = Pattern.compile( "^(([^/]+)/(?:.*/)?)([^/]+)$" );

			for ( File modFile : modFiles ) {
				if ( !keepRunning ) return false;

				ZipInputStream zis = null;
				try {
					log.info( "" );
					log.info( String.format( "Installing mod: %s", modFile.getName() ) );
					observer.patchingMod( modFile );

					zis = new ZipInputStream( new FileInputStream( modFile ) );
					ZipEntry item;
					while ( (item = zis.getNextEntry()) != null ) {
						if ( item.isDirectory() ) continue;

						Matcher m = pathPtn.matcher( item.getName() );
						if ( !m.matches() ) {
							log.warn( String.format( "Unexpected innerPath: %s", item.getName() ) );
							zis.closeEntry();
							continue;
						}

						String parentPath = m.group(1);
						String topFolder = m.group(2);
						String fileName = m.group(3);

						AbstractPack ftlP = topFolderMap.get( topFolder );
						if ( ftlP == null ) {
							log.warn( String.format( "Unexpected innerPath: %s", item.getName() ) );
							zis.closeEntry();
							continue;
						}

						if ( fileName.endsWith( ".xml.append" ) || fileName.endsWith( ".append.xml" ) ) {
							String innerPath = parentPath + fileName.replaceAll( "[.](?:xml[.]append|append[.]xml)$", ".xml" );
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							if ( !ftlP.contains( innerPath ) ) {
								log.warn( String.format( "Non-existent innerPath wasn't appended: %s", innerPath ) );
							}
							else {
								InputStream dstStream = null;
								try {
									dstStream = ftlP.getInputStream(innerPath);
									InputStream mergedStream = ModUtilities.appendXMLFile( zis, dstStream, ftlP.getName()+":"+innerPath, modFile.getName()+":"+parentPath+fileName );
									dstStream.close();
									ftlP.remove( innerPath );
									ftlP.add( innerPath, mergedStream );
								}
								finally {
									try {if ( dstStream != null ) dstStream.close();}
									catch ( IOException e ) {}
								}

								if ( !moddedItems.contains(innerPath) )
									moddedItems.add( innerPath );
							}
						}
						else {
							String innerPath = checkCase( item.getName(), knownPaths, knownPathsLower );

							if ( !moddedItems.contains(innerPath) )
								moddedItems.add( innerPath );
							else
								log.warn( String.format( "Clobbering earlier mods: %s", innerPath ) );

							if ( ftlP.contains( innerPath ) )
								ftlP.remove( innerPath );
							ftlP.add( innerPath, zis );
						}

						zis.closeEntry();
					}
				}
				finally {
					try {if (zis != null) zis.close();}
					catch ( Exception e ) {}

					System.gc();
				}

				modsInstalled++;
				observer.patchingProgress( progMilestone + progModsMax/modFiles.size()*modsInstalled, progMax );
			}
			progMilestone += progModsMax;
			observer.patchingProgress( progMilestone, progMax );

			// Prune 'removed' files from dats.
			for ( AbstractPack ftlP : new AbstractPack[]{dataP,resP} ) {
				if ( ftlP instanceof FTLPack ) {
					observer.patchingStatus( String.format( "Repacking \"%s\"...", ftlP.getName() ) );

					long bytesChanged = ((FTLPack)ftlP).repack().bytesChanged;
					log.info( String.format( "Repacked \"%s\" (%d bytes affected)", ftlP.getName(), bytesChanged ) );

					datsRepacked++;
					observer.patchingProgress( progMilestone + progRepackMax/allDats.length*datsRepacked, progMax );
				}
			}
			progMilestone += progRepackMax;
			observer.patchingProgress( progMilestone, progMax );

			observer.patchingProgress( 100, progMax );
			return true;
		}
		finally {
			try {if (dataP != null) dataP.close();}
			catch( Exception e ) {}

			try {if (resP != null) resP.close();}
			catch( Exception e ) {}
		}
	}


	/**
	 * Checks if an innerPath exists, ignoring letter case.
	 *
	 * If there is no collision, the innerPath is added to the known lists.
	 * A warning will be logged if a path with differing case exists.
	 *
	 * @param knownPaths a list of innerPaths seen so far
	 * @param knownPathsLower a copy of knownPaths, lower-cased
	 * @return the existing path (if different), or innerPath
	 */
	private String checkCase( String innerPath, List<String> knownPaths, List<String> knownPathsLower ) {
		if ( knownPaths.contains( innerPath ) ) return innerPath;

		String lowerPath = innerPath.toLowerCase();
		int lowerIndex = knownPathsLower.indexOf( lowerPath );
		if ( lowerIndex != -1 ) {
			String knownPath = knownPaths.get( lowerIndex );
			log.warn( String.format( "Modded file's case doesn't match existing path: \"%s\" vs \"%s\"", innerPath, knownPath ) );
			return knownPath;
		}

		knownPaths.add( innerPath );
		knownPathsLower.add( lowerPath );
		return innerPath;
	}



	public static class BackedUpDat {
		public File datFile = null;
		public File bakFile = null;
	}
}