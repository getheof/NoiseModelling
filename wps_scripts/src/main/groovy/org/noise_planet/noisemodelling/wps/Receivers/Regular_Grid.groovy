/**
 * @Author Aumond Pierre, Université Gustave Eiffel
 */

package org.noise_planet.noisemodelling.wps.Receivers

import geoserver.GeoServer
import geoserver.catalog.Store
import org.geotools.jdbc.JDBCDataStore
import org.h2gis.functions.spatial.crs.ST_SetSRID
import org.h2gis.functions.spatial.crs.ST_Transform
import org.h2gis.utilities.SFSUtilities
import org.h2gis.utilities.TableLocation
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader

import java.sql.*
import groovy.sql.Sql

title = 'Regular Grid'
description = 'Calculates a regular grid of receivers based on a single Geometry geom or a table tableName of Geometries with delta as offset in the Cartesian plane in meters.'

inputs = [buildingTableName : [name: 'Buildings table name', title: 'Buildings table name', type: String.class],
          fence           : [name         : 'Fence geometry', title: 'Extent filter', description: 'Create receivers only in the' +
                  ' provided polygon', min: 0, max: 1, type: Geometry.class],
          fenceTableName  : [name                                                         : 'Fence geometry from table', title: 'Filter using table bounding box',
                             description                                                  : 'Extract the bounding box of the specified table then create only receivers' +
                                     ' on the table bounding box' +
                                     '<br>  The table shall contain : </br>' +
                                     '- <b> THE_GEOM </b> : any geometry type. </br>', min: 0, max: 1, type: String.class],
          sourcesTableName: [name          : 'Sources table name', title: 'Sources table name', description: 'Keep only receivers at least at 1 meters of' +
                  ' provided sources geometries' +
                  '<br>  The table shall contain : </br>' +
                  '- <b> THE_GEOM </b> : any geometry type. </br>', min: 0, max: 1, type: String.class],
          delta             : [name: 'offset', title: 'offset', description: 'Offset in the Cartesian plane in meters', type: Double.class],
          receiverstablename: [name: 'receiverstablename', description: 'Do not write the name of a table that contains a space. (default : RECEIVERS)', title: 'Name of receivers table', min: 0, max: 1, type: String.class],
          height            : [name: 'height', title: 'height', description: 'Height of receivers in meters', min: 0, max: 1, type: Double.class]]

outputs = [tableNameCreated: [name: 'tableNameCreated', title: 'tableNameCreated', type: String.class]]

static Connection openGeoserverDataStoreConnection(String dbName) {
    if (dbName == null || dbName.isEmpty()) {
        dbName = new GeoServer().catalog.getStoreNames().get(0)
    }
    Store store = new GeoServer().catalog.getStore(dbName)
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}


def run(input) {

    // Get name of the database
    String dbName = ""
    if (input['databaseName']) {
        dbName = input['databaseName'] as String
    }

    // Open connection
    openGeoserverDataStoreConnection(dbName).withCloseable { Connection connection -> exec(connection, input)
    }
}


def exec(connection, input) {

    String receivers_table_name = "RECEIVERS"
    if (input['receiverstablename']) {
        receivers_table_name = input['receiverstablename']
    }
    receivers_table_name = receivers_table_name.toUpperCase()

    Double delta = 10
    if (input['delta']) {
        delta = input['delta']
    }

    Double h = 4
    if (input['height']) {
        h = input['height']
    }

    String sources_table_name = "SOURCES"
    if (input['sourcesTableName']) {
        sources_table_name = input['sourcesTableName']
    }
    sources_table_name = sources_table_name.toUpperCase()

    String building_table_name = "BUILDINGS"
    if (input['buildingTableName']) {
        building_table_name = input['buildingTableName']
    }
    building_table_name = building_table_name.toUpperCase()

    int srid = SFSUtilities.getSRID(connection, TableLocation.parse(building_table_name))

    Sql sql = new Sql(connection)
    //Delete previous receivers grid.
    sql.execute(String.format("DROP TABLE IF EXISTS %s", receivers_table_name))
    String queryGrid = null


    // Reproject fence
    int targetSrid = SFSUtilities.getSRID(connection, TableLocation.parse(building_table_name))
    if (targetSrid == 0 && input['sourcesTableName']) {
        targetSrid = SFSUtilities.getSRID(connection, TableLocation.parse(sources_table_name))
    }

    Geometry fenceGeom = null
    if (input['fence']) {
        if (targetSrid != 0) {
            // Transform fence to the same coordinate system than the buildings & sources
            WKTReader wktReader = new WKTReader()
            fence = wktReader.read(input['fence'] as String)
            fenceGeom = ST_Transform.ST_Transform(connection, ST_SetSRID.setSRID(fence, 4326), targetSrid)
        } else {
            System.err.println("Unable to find buildings or sources SRID, ignore fence parameters")
        }
    } else if (input['fenceTableName']) {
        fenceGeom = (new GeometryFactory()).toGeometry(SFSUtilities.getTableEnvelope(connection, TableLocation.parse(input['fenceTableName'] as String), "THE_GEOM"))
    } else {
        fenceGeom = (new GeometryFactory()).toGeometry(SFSUtilities.getTableEnvelope(connection, TableLocation.parse(building_table_name), "THE_GEOM"))
    }

    sql.execute("CREATE TABLE " + receivers_table_name + "(PK SERIAL, THE_GEOM GEOMETRY) AS SELECT null, ST_SETSRID(ST_UPDATEZ(THE_GEOM, "+h+"), "+srid+") THE_GEOM FROM ST_MakeGridPoints(ST_GeomFromText('"+fenceGeom+"')," + delta + "," + delta + ");")

    if (input['buildingTableName']) {
        System.out.println("Delete receivers inside buildings")
        sql.execute("Create spatial index on " + building_table_name + "(the_geom);")
        sql.execute("delete from " + receivers_table_name + " g where exists (select 1 from " + building_table_name + " b where ST_Z(g.the_geom) < b.HEIGHT and g.the_geom && b.the_geom and ST_INTERSECTS(g.the_geom, b.the_geom) and ST_distance(b.the_geom, g.the_geom) < 1 limit 1);")
    }
    if (input['sourcesTableName']) {
        System.out.println("Delete receivers near sources")
        sql.execute("Create spatial index on " + sources_table_name + "(the_geom);")
        sql.execute("delete from " + receivers_table_name + " g where exists (select 1 from " + sources_table_name + " r where st_expand(g.the_geom, 1) && r.the_geom and st_distance(g.the_geom, r.the_geom) < 1 limit 1);")
    }

    return [tableNameCreated: "Process done. Table of receivers " + receivers_table_name + " created !"]
}

