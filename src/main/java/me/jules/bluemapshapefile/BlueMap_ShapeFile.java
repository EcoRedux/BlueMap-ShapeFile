package me.jules.bluemapshapefile;

import com.flowpowered.math.vector.Vector3d;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Line;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.geotools.data.*;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

public class BlueMap_ShapeFile extends JavaPlugin {


    FileConfiguration cfg;

    @Override
    public void onEnable() {
        /* Get bluemap */
        BlueMapAPI.onEnable(e -> {
            try{
                activate();
            }catch(IOException ex){
                ex.printStackTrace();
                getServer().getPluginManager().disablePlugin(this);
            }
        });
    }

    /**
     * Used to load config.yml and various resources (shapefile related files, countries.txt)
     * @param resource file path
     * @return File
     */
    public File loadResource(String resource) {
        File folder = getDataFolder();
        if (!folder.exists()) {
            if (!folder.mkdir()) {
                this.getLogger().warning("Resource " + resource + " could not be loaded. Data folder could not be made");
                return null;
            }
        }
        File resourceFile = new File(folder, resource);
        try {
            // if file not already there, it is loaded from the jar
            // if it is not in the jar, give up
            if (!resourceFile.exists()) {
                if (!resourceFile.createNewFile()) {
                    this.getLogger().warning("Resource " + resource + " could not be created");
                    return null;
                }
                try (InputStream in = this.getResource(resource);
                     OutputStream out = new FileOutputStream(resourceFile)) {
                    if (in == null || out == null) {
                        this.getLogger().warning("Resource " + resource + " could not be located");
                        return resourceFile;
                    }
                    ByteStreams.copy(in, out);
                }
                // I don't think this section of code is ever reached. But doesn't hurt to leave it in
                if (!resourceFile.isFile() || resourceFile.length() == 0) {
                    resourceFile.delete();
                    this.getLogger().warning("Resource " + resource + " was not found");
                    return resourceFile;
                }
            }
        } catch (Exception e) { // file stuff always can have exceptions. stay safe
            e.printStackTrace();
        }
        return resourceFile;
    }

    /**
     * Handles everything
     * @throws IOException if something happens
     */
    private void activate() throws IOException {
        try {

        } catch (NullPointerException e) { // api is not null, it accesses some object I cant access that is null, which causes this
            this.getLogger().severe("Error loading BlueMap Marker API! Is BlueMap disabled?");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        File configFile = this.loadResource("config.yml");
        if (configFile == null) {
            return;
        }
        this.cfg = YamlConfiguration.loadConfiguration(configFile);

        for (String section : cfg.getConfigurationSection("shapefiles").getKeys(false)) {
            section = "shapefiles." + section;
            String fileName = cfg.getString(section + "." + "shapefileName", "countryborders");
            double scaling = 120000 / cfg.getDouble(section + "." + "scaling", 1000);
            double xOffset = cfg.getDouble(section + "." + "xOffset", 0);
            double yMarker = cfg.getInt(section + "." + "y", 64);
            double zOffset = cfg.getDouble(section + "." + "zOffset", 0);

            String desc = cfg.getString(section + "." + "description", null);
            if (desc != null && desc.equalsIgnoreCase("none")) {
                desc = null;
            }

            String layerName = cfg.getString(section + "." + "layer", "Borders");
            Optional<BlueMapWorld> world = BlueMapAPI.getInstance().get().getWorld(cfg.getString(section + "." + "world"));

            for (BlueMapMap map : world.get().getMaps()) {
                MarkerSet markerSet = map.getMarkerSets().get(layerName);
                if (markerSet == null) {
                    markerSet = MarkerSet.builder()
                            .label(layerName)
                            .defaultHidden(cfg.getBoolean("layers." + section + ".hideByDefault", true))
                            .build();
                }

                boolean errors = false;

                File shapefile = new File(this.getDataFolder(), fileName + ".shp");
                if (shapefile == null || !shapefile.isFile()) {
                    shapefile = this.loadResource(fileName + ".shp");
                    if (!shapefile.isFile() || shapefile.length() == 0) {
                        this.getLogger().warning("Shapefile " + fileName + " not found!");
                        shapefile.delete();
                        continue;
                    } else {
                        File shx = this.loadResource(fileName + ".shx");
                        File prj = this.loadResource(fileName + ".prj");
                        File dbf = this.loadResource(fileName + ".dbf");
                        List<File> additionalFiles = List.of(shx, prj, dbf);

                        boolean exit = false;
                        for (File file : additionalFiles) {
                            if (!file.isFile() || file.length() == 0) {
                                this.getLogger().warning(file.getName() + " not found! ");
                                file.delete();
                                exit = true;
                                continue;
                            }
                        }
                        if (exit) {
                            this.getLogger().warning("One or more additional files could not be located for shapefile " + fileName + ".shp");
                            this.getLogger().warning("Shapefile" + fileName + ".shp not loaded!");
                            continue;
                        }
                    }
                }

                FileDataStore store = FileDataStoreFinder.getDataStore(shapefile);

                SimpleFeatureSource featureSource = store.getFeatureSource();

                if (world == null) {
                    this.getLogger().severe("No world found!");
                    store.dispose();
                    this.getServer().getPluginManager().disablePlugin(this);
                    return;
                }

                if (featureSource.getSchema() == null || featureSource.getSchema().getCoordinateReferenceSystem() == null) {
                    this.getLogger().warning("Could not load .prj file for Shapefile " + fileName + ".");
                    store.dispose();
                    continue;
                }
                CoordinateReferenceSystem data = featureSource.getSchema().getCoordinateReferenceSystem();
                String code = data.getName().getCode();
                this.getLogger().info("Shapefile format: " + code);
                if (!code.contains("WGS_1984") && !code.contains("wgs_1984")) {
                    this.getLogger().info("Translating " + fileName + " to a readable format...");
                    try {
                        this.translateCRS(featureSource, shapefile);
                        this.getLogger().info("Translating finished.");
                    } catch (FactoryException e) {
                        e.printStackTrace();
                        store.dispose();
                        continue;
                    }
                }

                FeatureCollection<SimpleFeatureType, SimpleFeature> features;
                try {
                    features = featureSource.getFeatures();
                } catch (IOException e) {
                    e.printStackTrace();
                    store.dispose();
                    this.getServer().getPluginManager().disablePlugin(this);
                    return;
                }

                int iteration = -1;
                try (FeatureIterator<SimpleFeature> iterator = features.features()) {
                    while (iterator.hasNext()) {
                        iteration++;
                        SimpleFeature feature = iterator.next();
                        int index = 0;
                        for (Property property : feature.getProperties()) {
                            index++;
                            if (property.getValue() == null || property.getValue().toString() == null) {
                                errors = true;
                                continue;
                            }
                            String propertyValue = property.getValue().toString();
                            if (!propertyValue.contains("((")) continue;

                            String[] polygons = {propertyValue};
                            if (propertyValue.contains("), (")) {
                                polygons = propertyValue.split(Pattern.quote("), ("));
                            }

                            int polygonIndex = 0;
                            for (String polygon : polygons) {
                                Line.Builder lineBuilder = Line.builder();

                                String idBase = section + "_" + iteration + "_" + index + "_" + polygonIndex;
                                int segmentIndex = 0;
                                Double prevVal = null;

                                polygon = polygon.replace("MULTIPOLYGON ", "").replace("(", "").replace(")", "");
                                String[] locations = polygon.split(", ");

                                double[] x = new double[locations.length];
                                double[] y = new double[locations.length];
                                double[] z = new double[locations.length];
                                int i = 0;

                                for (String location : locations) {
                                    String[] coords = location.split(" ");
                                    double lat = 0;
                                    double lon = 0;
                                    try {
                                        lat = Double.valueOf(coords[0]);
                                        lon = Double.valueOf(coords[1]);
                                    } catch (NumberFormatException e) {
                                        e.printStackTrace();
                                        continue;
                                    }

                                    if (prevVal != null && Math.abs(lat - prevVal) > 200) {

                                        String currentId = idBase + "_seg_" + segmentIndex;
                                        if (markerSet.get(currentId) != null) markerSet.remove(currentId);

                                        Line line = lineBuilder.build();
                                        if (line.getPoints().length > 1) {
                                            LineMarker lineMarker = LineMarker.builder()
                                                    .line(line)
                                                    .label(desc)
                                                    .depthTestEnabled(false)
                                                    .build();
                                            markerSet.put(currentId, lineMarker);
                                        }

                                        segmentIndex++;
                                        lineBuilder = Line.builder();
                                    }
                                    prevVal = lat;

                                    if (lat > 180 || lon + 90 > 180) {
                                        errors = true;
                                    }
                                    x[i] = (lat * scaling) + xOffset;
                                    y[i] = yMarker;
                                    z[i] = (lon * scaling) * -1 + zOffset;

                                    lineBuilder.addPoint(Vector3d.from(x[i], y[i], z[i]));
                                    i++;
                                }

                                String finalId = idBase + "_seg_" + segmentIndex;
                                if(markerSet.get(finalId) != null){
                                    markerSet.remove(finalId);
                                }
                                Line line = lineBuilder.build();

                                if (line.getPoints().length > 1) {
                                    LineMarker lineMarker = LineMarker.builder()
                                            .line(line)
                                            .label(desc)
                                            .depthTestEnabled(false)
                                            .build();

                                    if (lineMarker == null) {
                                        this.getLogger().info("Error adding line marker! " + finalId);
                                        continue;
                                    }
                                    markerSet.put(finalId, lineMarker);
                                }
                                polygonIndex++;
                            }
                        }
                        map.getMarkerSets().put("borders." + layerName, markerSet);
                    }
                } catch (Exception e) { // can happen from a bad cast or something
                    store.dispose();
                    e.printStackTrace();
                    this.getServer().getPluginManager().disablePlugin(this);
                    return;
                } finally {
                    store.dispose();
                }
                if (errors) {
                    this.getLogger().warning("Shapefile " + fileName + " had errors on load and may be partially" +
                            " or completely unloaded. Shapefile is likely incorrectly formatted, or goes outside of normal borders.");
                } else {
                    this.getLogger().info("Shapefile " + fileName + " successfully loaded!");
                }
            }
        }

        if (cfg.getBoolean("countryMarkers.enable", true)) {
            handleCountryMarkers();
        }

        this.getLogger().info("Version " + this.getDescription().getVersion() + " is activated!");
    }

    /**
     * Handles country markers
     * Only run after activate()
     * @throws IOException
     */
    public void handleCountryMarkers() throws IOException {
        File countriesSetFile = this.loadResource("countries.txt"); // just loading it into the plugin dir so it can be accessed
        FileReader reader = new FileReader(countriesSetFile);
        if (reader == null) {
            this.getLogger().warning("Countries file not found. Country markers not loaded.");
            return;
        }

        String worldName = cfg.getString("countryMarkers.world", "world");
        World world = Bukkit.getWorld(worldName);
        Optional<BlueMapWorld> bmWorld = BlueMapAPI.getInstance().get().getWorld(world);

        if (bmWorld == null) {
            this.getLogger().warning("World name for country markers is null or is not registered in BlueMap! Country markers not loaded.");
            return;
        }

        for (BlueMapMap map : bmWorld.get().getMaps()) {
            String layerName = cfg.getString("countryMarkers.layer", "Borders");
            MarkerSet markerSet = map.getMarkerSets().get("borders." + layerName);

            if(markerSet == null){
                markerSet = MarkerSet.builder()
                        .label(layerName)
                        .defaultHidden(true)
                        .build();
            }

            double scaling = 120000 / cfg.getDouble("countryMarkers.scaling", 1000);

            for (String string : CharStreams.readLines(reader)) {
                String[] separated = string.split("\t");

                double xOffset = cfg.getDouble("countryMarkers.xOffset", 0);
                double zOffset = cfg.getDouble("countryMarkers.zOffset", 0);

                double x = (Double.valueOf(separated[2]) * scaling) + xOffset;
                double y = cfg.getInt("countryMarkers.y", 64);
                double z = (Double.valueOf(separated[1]) * scaling * -1) + zOffset;



                POIMarker marker = POIMarker.builder()
                        .defaultIcon()
                        .label(separated[3])
                        .position(x, y, z)
                        .build();

                markerSet.put(separated[3], marker);
                map.getMarkerSets().put("borders." + layerName, markerSet);
            }
            this.getLogger().info("Country markers enabled!");
        }
    }

    public void translateCRS(SimpleFeatureSource featureSource, File shapefile) throws FactoryException, IOException {
        SimpleFeatureType schema = featureSource.getSchema();

        CoordinateReferenceSystem otherCRS = schema.getCoordinateReferenceSystem();
        CoordinateReferenceSystem worldCRS = DefaultGeographicCRS.WGS84;

        MathTransform transform = CRS.findMathTransform(otherCRS, worldCRS, true);
        SimpleFeatureCollection featureCollection = featureSource.getFeatures();

        // how do i file
        File newFile = new File(shapefile.getParent(), shapefile.getName() + "copying.shp");
        newFile.createNewFile();

        DataStoreFactorySpi factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> create = new HashMap<String, Serializable>();
        create.put("url", newFile.toURI().toURL());
        create.put("create spatial index", Boolean.TRUE);
        DataStore dataStore = factory.createNewDataStore(create);
        SimpleFeatureType featureType = SimpleFeatureTypeBuilder.retype(schema, worldCRS);
        dataStore.createSchema(featureType);

        String createdName = dataStore.getTypeNames()[0];

        Transaction transaction = new DefaultTransaction("Reproject");
        FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
                dataStore.getFeatureWriterAppend(createdName, transaction);
        SimpleFeatureIterator iterator = featureCollection.features();
        try {
            while (iterator.hasNext()) {
                // copy the contents of each feature and transform the geometry
                SimpleFeature feature = iterator.next();
                SimpleFeature copy = writer.next();
                copy.setAttributes(feature.getAttributes());

                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                Geometry geometry2 = JTS.transform(geometry, transform);

                copy.setDefaultGeometry(geometry2);
                writer.write();
            }
        } catch (Exception e) {
            e.printStackTrace();
            transaction.rollback();
        } finally {
            writer.close();
            iterator.close();
            transaction.commit();
            transaction.close();
        }

        // kinda sketchy way of deleting the copy files
        File folder = shapefile.getParentFile();
        for (File file : folder.listFiles()) {
            if (file.getName().contains(shapefile.getName() + "copying")) {
                file.delete();
            }
        }
    }

}