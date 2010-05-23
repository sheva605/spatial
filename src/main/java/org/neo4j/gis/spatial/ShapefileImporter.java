/*
 * Copyright (c) 2010
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;

import org.geotools.data.shapefile.ShpFiles;
import org.geotools.data.shapefile.dbf.DbaseFileHeader;
import org.geotools.data.shapefile.dbf.DbaseFileReader;
import org.geotools.data.shapefile.prj.PrjFileReader;
import org.geotools.data.shapefile.shp.ShapefileException;
import org.geotools.data.shapefile.shp.ShapefileReader;
import org.geotools.data.shapefile.shp.ShapefileReader.Record;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;


/**
 * @author Davide Savazzi
 */
public class ShapefileImporter implements Constants {
	
	// Constructor

	public ShapefileImporter(GraphDatabaseService database) {	
		this(database, 1000);
	}
	
	public ShapefileImporter(GraphDatabaseService database, int commitInterval) {
		if (commitInterval < 1) throw new IllegalArgumentException("commitInterval must be >= 1");
		
		this.database = database;
		this.spatialDatabase = new SpatialDatabaseService(database);
		this.commitInterval = commitInterval;
	}
	
	
	// Main
	
	public static void main(String[] args) throws Exception {
		String neoPath;
		String shpPath;
		String layerName;
		int commitInterval = 1000;

		if (args.length < 2 || args.length > 4) {
			throw new IllegalArgumentException("Parameters: neo4jDirectory shapefile [layerName commitInterval]");
		}
		
		neoPath = args[0];
		
		shpPath = args[1];
		// remove extension
		shpPath = shpPath.substring(0, shpPath.lastIndexOf("."));
		
		if (args.length == 2) {
			layerName = shpPath.substring(shpPath.lastIndexOf(File.separator) + 1);
		} else if (args.length == 3) {
			layerName = args[2];
		} else {
			layerName = args[2];
			commitInterval = Integer.parseInt(args[3]);
		}
		
		GraphDatabaseService database = new EmbeddedGraphDatabase(neoPath);
		try {
	        ShapefileImporter importer = new ShapefileImporter(database, commitInterval);
	        importer.importShapefile(shpPath, layerName);
	    } finally {
			database.shutdown();
		}
	}

	
	// Public methods
	
	public void importShapefile(String dataset, String layerName) throws ShapefileException, FileNotFoundException, IOException {
		Layer layer = getOrCreateLayer(layerName);
		GeometryFactory geomFactory = layer.getGeometryFactory();
		
		boolean strict = false;
		boolean shpMemoryMapped = true;
		
		long startTime = System.currentTimeMillis();
		
		ShpFiles shpFiles = new ShpFiles(new File(dataset + ".shp"));
		
		PrjFileReader prjReader = new PrjFileReader(shpFiles);
		try {
			CoordinateReferenceSystem crs = prjReader.getCoodinateSystem();
			if (crs != null) {
				Transaction tx = database.beginTx();
				try {
					layer.setCoordinateReferenceSystem(crs);
					tx.success();
				} finally {
					tx.finish();
				}					
			}
		} finally {
			prjReader.close();
		}
		
		ShapefileReader shpReader = new ShapefileReader(shpFiles, strict, shpMemoryMapped, geomFactory);
		try {
			// TODO ask charset to user?
			DbaseFileReader dbfReader = new DbaseFileReader(shpFiles, shpMemoryMapped, Charset.defaultCharset());
			try {
				DbaseFileHeader dbaseFileHeader = dbfReader.getHeader();
				
				String[] fieldsName = new String[dbaseFileHeader.getNumFields()];
				for (int i = 0; i < fieldsName.length; i++) {
					fieldsName[i] = dbaseFileHeader.getFieldName(i);
				}
				
				Transaction tx = database.beginTx();
				try {
					layer.mergeExtraPropertyNames(fieldsName);
					tx.success();
				} finally {
					tx.finish();
				}
				
				Record record;
				Geometry geometry;
				Object[] fields;
				int recordCounter = 0;
				while (shpReader.hasNext() && dbfReader.hasNext()) {
					tx = database.beginTx();
					try {
						for (int i = 0; i < commitInterval; i++) {
							if (shpReader.hasNext() && dbfReader.hasNext()) {
								record = shpReader.nextRecord();
								recordCounter++;
								try {
									geometry = (Geometry) record.shape();
									fields = dbfReader.readEntry();
									
									if (geometry.isEmpty()) {
										log("warn | found empty geometry in record " + recordCounter);
									} else {
										// TODO check geometry.isValid() ?
										layer.add(geometry, fieldsName, fields);
									}
								} catch (IllegalArgumentException e) {
									// org.geotools.data.shapefile.shp.ShapefileReader.Record.shape() can throw this exception
									log("warn | found invalid geometry: index=" + recordCounter, e);					
								}
							}
						}
						
						log("info | inserted geometries: " + recordCounter);
						tx.success();
					} finally {
						tx.finish();
					}
				}
			} finally {
				dbfReader.close();
			}			
		} finally {
			shpReader.close();
		}

		long stopTime = System.currentTimeMillis();
		log("info | elapsed time in seconds: " + ((stopTime - startTime) / 1000));
	}
	
	
	// Private methods
	
	private Layer getOrCreateLayer(String layerName) {
		Layer layer;
		Transaction tx = database.beginTx();
		try {
			if (spatialDatabase.containsLayer(layerName)) {
				layer = spatialDatabase.getLayer(layerName);				
			} else {
				layer = spatialDatabase.createLayer(layerName);
			}
			tx.success();
		} finally {
			tx.finish();
		}
		return layer;
	}
	
	private void log(String message) {
		System.out.println(message);
	}

	private void log(String message, Exception e) {
		System.out.println(message);
		e.printStackTrace();
	}
	
	
	// Attributes
	
	private GraphDatabaseService database;
	private SpatialDatabaseService spatialDatabase;
	private int commitInterval;
}