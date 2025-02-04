/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.tooling.features;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;

import org.apache.karaf.features.internal.model.Bundle;
import org.apache.karaf.features.internal.model.Dependency;
import org.apache.karaf.features.internal.model.Feature;
import org.apache.karaf.features.internal.model.Features;
import org.apache.karaf.features.internal.model.JaxbUtil;
import org.apache.karaf.features.internal.model.ObjectFactory;
import org.apache.karaf.tooling.utils.DependencyHelper;
import org.apache.karaf.tooling.utils.DependencyHelperFactory;
import org.apache.karaf.tooling.utils.ManifestUtils;
import org.apache.karaf.tooling.utils.MojoSupport;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.xml.sax.SAXException;

import static org.apache.karaf.deployer.kar.KarArtifactInstaller.FEATURE_CLASSIFIER;

/**
 * Generates the features XML file starting with an optional source feature.xml and adding
 * project dependencies as bundles and feature/car dependencies.
 * 
 * NB this requires a recent maven-install-plugin such as 2.3.1
 */
@Mojo(name = "features-generate-descriptor", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class GenerateDescriptorMojo extends MojoSupport {

    /**
     * An (optional) input feature file to extend. The plugin reads this file, and uses it as a template
     * to create the output.
     * This is highly recommended as it is the only way to add <code>&lt;feature/&gt;</code>
     * elements to the individual features that are generated.  Note that this file is filtered using standard Maven
     * resource interpolation, allowing attributes of the input file to be set with information such as ${project.version}
     * from the current build.
     * <p/>
     * When dependencies are processed, if they are duplicated in this file, the dependency here provides the baseline
     * information and is supplemented by additional information from the dependency.
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/feature/feature.xml")
    private File inputFile;

    /**
     * (wrapper) The filtered input file. This file holds the result of Maven resource interpolation and is generally
     * not necessary to change, although it may be helpful for debugging.
     */
    @Parameter(defaultValue = "${project.build.directory}/feature/filteredInputFeature.xml")
    private File filteredInputFile;

    /**
     * The file to generate.  This file is attached as a project output artifact with the classifier specified by
     * <code>attachmentArtifactClassifier</code>.
     */
    @Parameter(defaultValue = "${project.build.directory}/feature/feature.xml")
    private File outputFile;

    /**
     * Exclude some artifacts from the generated feature.
     * See addBundlesToPrimaryFeature for more details.
     *
     */
    @Parameter
    private List<String> excludedArtifactIds = new ArrayList<String>();

    /**
     * The resolver to use for the feature.  Normally null or "OBR" or "(OBR)"
     */
    @Parameter(defaultValue = "${resolver}")
    private String resolver;

    /**
     * The artifact type for attaching the generated file to the project
     */
    @Parameter(defaultValue = "xml")
    private String attachmentArtifactType = "xml";

    /**
     * (wrapper) The artifact classifier for attaching the generated file to the project
     */
    @Parameter(defaultValue = "features")
    private String attachmentArtifactClassifier = "features";

    /**
     * Specifies whether features dependencies of this project will be included inline in the
     * final output (<code>true</code>), or simply referenced as output artifact dependencies (<code>false</code>).
     * If <code>true</code>, feature dependencies' xml descriptors are read and their contents added to the features descriptor under assembly.
     * If <code>false</code>, feature dependencies are added to the assembled feature as dependencies.
     * Setting this value to <code>true</code> is especially helpful in multiproject builds where subprojects build their own features
     * using <code>aggregateFeatures = false</code>, then combined with <code>aggregateFeatures = true</code> in an
     * aggregation project with explicit dependencies to the child projects.
     */
    @Parameter(defaultValue = "false")
    private boolean aggregateFeatures = false;

    /**
     * If present, the bundles added to the feature constructed from the dependencies will be marked with this default
     * startlevel.  If this parameter is not present, no startlevel attribute will be created. Finer resolution for specific
     * dependencies can be obtained by specifying the dependency in the file referenced by the <code>inputFile</code> parameter.
     */
    @Parameter
    private Integer startLevel;

    /**
     * Installation mode. If present, generate "feature.install" attribute:
     * <p/>
     * <a href="http://karaf.apache.org/xmlns/features/v1.1.0">Installation mode</a>
     * <p/>
     * Can be either manual or auto. Specifies whether the feature should be automatically installed when
     * dropped inside the deploy folder. Note: this attribute doesn't affect feature descriptors that are installed
     * from the feature:install command or as part of the etc/org.apache.karaf.features.cfg file.
     */
    @Parameter
    private String installMode;

    /**
     * Flag indicating whether transitive dependencies should be included (<code>true</code>) or not (<code>false</code>).
     * <p/>
     * N.B. Note the default value of this is true, but is suboptimal in cases where specific <code>&lt;feature/&gt;</code> dependencies are
     * provided by the <code>inputFile</code> parameter.
     */
    @Parameter(defaultValue = "true")
    private boolean includeTransitiveDependency;

    /**
     * The standard behavior is to add dependencies as <code>&lt;bundle&gt;</code> elements to a <code>&lt;feature&gt;</code>
     * with the same name as the artifactId of the project.  This flag disables that behavior.
     * If this parameter is <code>true</code>, then two other parameters refine the list of bundles added to the primary feature:
     * <code>excludedArtifactIds</code> and <code>ignoreScopeProvided</code>. Each of these specifies dependent artifacts
     * that should <strong>not</strong> be added to the primary feature.
     * <p>
     *     Note that you may tune the <code>bundle</code> elements by including them in the <code>inputFile</code>.
     *     If the <code>inputFile</code> has a <code>feature</code> element for the primary feature, the plugin will
     *     respect it, so that you can, for example, set the <code>startLevel</code> or <code>start</code> attribute.
     * </p>
     *
     */
    @Parameter(defaultValue = "true")
    private boolean addBundlesToPrimaryFeature;

    /**
     * The standard behavior is to add any dependencies other than those in the <code>runtime</code> scope to the feature bundle.
     * Setting this flag to "true" disables adding any dependencies (transient or otherwise) that are in
     * <code>&lt;scope&gt;provided&lt;/scope&gt;</code>. See <code>addBundlesToPrimaryFeature</code> for more details.
     */
    @Parameter(defaultValue = "false")
    private boolean ignoreScopeProvided;

    /**
     * Flag indicating whether the main project artifact should be included (<code>true</code>) or not (<code>false</code>).
     * This parameter is useful when you add an execution of this plugin to a project with some packaging that is <strong>not</strong>
     * <code>feature</code>. If you don't set this, then you will get a feature that contains the dependencies but
     * not the primary artifact itself.
     * <p/>
     * Assumes the main project artifact is a bundle and the feature will be attached alongside using <code>attachmentArtifactClassifier</code>.
     */
    @Parameter(defaultValue = "false")
    private boolean includeProjectArtifact;

    // *************************************************
    // READ-ONLY MAVEN PLUGIN PARAMETERS
    // *************************************************

    /**
     * We can't autowire strongly typed RepositorySystem from Aether because it may be Sonatype (Maven 3.0.x)
     * or Eclipse (Maven 3.1.x/3.2.x) implementation, so we switch to service locator.
     */
    @Component
    private PlexusContainer container;

    @Component
    protected MavenResourcesFiltering mavenResourcesFiltering;

    @Component
    protected MavenFileFilter mavenFileFilter;

    // dependencies we are interested in
    protected Map<?, String> localDependencies;

    // log of what happened during search
    protected String treeListing;

    // an access layer for available Aether implementation
    protected DependencyHelper dependencyHelper;

    // maven log
    private Log log;

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            this.dependencyHelper = DependencyHelperFactory.createDependencyHelper(this.container, this.project, this.mavenSession, getLog());
            this.dependencyHelper.getDependencies(project, includeTransitiveDependency);
            this.localDependencies = dependencyHelper.getLocalDependencies();
            this.treeListing = dependencyHelper.getTreeListing();
            File dir = outputFile.getParentFile();
            if (dir.isDirectory() || dir.mkdirs()) {
                PrintStream out = new PrintStream(new FileOutputStream(outputFile));
                try {
                    writeFeatures(out);
                } finally {
                    out.close();
                }
                // now lets attach it
                projectHelper.attachArtifact(project, attachmentArtifactType, attachmentArtifactClassifier, outputFile);

            } else {
                throw new MojoExecutionException("Could not create directory for features file: " + dir);
            }
        } catch (Exception e) {
            getLog().error(e.getMessage());
            throw new MojoExecutionException("Unable to create features.xml file: " + e, e);
        }
    }

    /*
     * Write all project dependencies as feature
     */
    private void writeFeatures(PrintStream out) throws ArtifactResolutionException, ArtifactNotFoundException,
            IOException, JAXBException, SAXException, ParserConfigurationException, XMLStreamException, MojoExecutionException {
        getLog().info("Generating feature descriptor file " + outputFile.getAbsolutePath());
        //read in an existing feature.xml
        ObjectFactory objectFactory = new ObjectFactory();
        Features features;
        if (inputFile.exists()) {
            filter(inputFile, filteredInputFile);
            features = readFeaturesFile(filteredInputFile);
        } else {
            features = objectFactory.createFeaturesRoot();
        }
        if (features.getName() == null) {
            features.setName(project.getArtifactId());
        }

        Feature feature = null;
        for (Feature test : features.getFeature()) {
            if (test.getName().equals(project.getArtifactId())) {
                feature = test;
            }
        }
        if (feature == null) {
            feature = objectFactory.createFeature();
            feature.setName(project.getArtifactId());
        }
        if (!feature.hasVersion()) {
            feature.setVersion(project.getArtifact().getBaseVersion());
        }
        if (feature.getDescription() == null) {
            feature.setDescription(project.getName());
        }
        if (resolver != null) {
            feature.setResolver(resolver);
        }
        if (installMode != null) {
            feature.setInstall(installMode);
        }
        if (project.getDescription() != null && feature.getDetails() == null) {
            feature.setDetails(project.getDescription());
        }
        if (includeProjectArtifact) {
            Bundle bundle = objectFactory.createBundle();
            bundle.setLocation(this.dependencyHelper.artifactToMvn(project.getArtifact()));
            if (startLevel != null) {
                bundle.setStartLevel(startLevel);
            }
            feature.getBundle().add(bundle);
        }
        for (Map.Entry<?, String> entry : localDependencies.entrySet()) {
            Object artifact = entry.getKey();

            if (excludedArtifactIds.contains(this.dependencyHelper.getArtifactId(artifact))) {
                continue;
            }

            if (this.dependencyHelper.isArtifactAFeature(artifact)) {
                if (aggregateFeatures && FEATURE_CLASSIFIER.equals(this.dependencyHelper.getClassifier(artifact))) {
                    File featuresFile = this.dependencyHelper.resolve(artifact, getLog());
                    if (featuresFile == null || !featuresFile.exists()) {
                        throw new MojoExecutionException("Cannot locate file for feature: " + artifact + " at " + featuresFile);
                    }
                    Features includedFeatures = readFeaturesFile(featuresFile);
                    //TODO check for duplicates?
                    features.getFeature().addAll(includedFeatures.getFeature());
                }
            } else if (addBundlesToPrimaryFeature) {
                String bundleName = this.dependencyHelper.artifactToMvn(artifact);
                File bundleFile = this.dependencyHelper.resolve(artifact, getLog());
                Manifest manifest = getManifest(bundleFile);

                if (manifest == null || !ManifestUtils.isBundle(getManifest(bundleFile))) {
                    bundleName = "wrap:" + bundleName;
                }

                Bundle bundle = null;
                for (Bundle b : feature.getBundle()) {
                    if (bundleName.equals(b.getLocation())) {
                        bundle = b;
                        break;
                    }
                }
                if (bundle == null) {
                    bundle = objectFactory.createBundle();
                    bundle.setLocation(bundleName);
                    if (!"provided".equals(entry.getValue()) || !ignoreScopeProvided) {
                        feature.getBundle().add(bundle);
                    }
                }
                if ("runtime".equals(entry.getValue())) {
                    bundle.setDependency(true);
                }
                if (startLevel != null && bundle.getStartLevel() == 0) {
                    bundle.setStartLevel(startLevel);
                }
            }
        }

        if ((!feature.getBundle().isEmpty() || !feature.getFeature().isEmpty()) && !features.getFeature().contains(feature)) {
            features.getFeature().add(feature);
        }

        JaxbUtil.marshal(features, out);
        try {
            checkChanges(features, objectFactory);
        } catch (Exception e) {
            throw new MojoExecutionException("Features contents have changed", e);
        }
        getLog().info("...done!");
    }

    /**
     * Extract the MANIFEST from the give file.
     */

    private Manifest getManifest(File file) throws IOException {
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(file));
        } catch (Exception e) {
            getLog().warn("Error while opening artifact", e);
            return null;
        }

        try {
            is.mark(256 * 1024);
            JarInputStream jar = new JarInputStream(is);
            Manifest m = jar.getManifest();
            if (m == null) {
                getLog().warn("Manifest not present in the first entry of the zip - " + file.getName());
            }
            jar.close();
            return m;
        } finally {
            if (is != null) { // just in case when we did not open bundle
                is.close();
            }
        }
    }

    private Features readFeaturesFile(File featuresFile) throws XMLStreamException, JAXBException, IOException {
        return JaxbUtil.unmarshal(featuresFile.toURI().toASCIIString(), false);
    }

    public void setLog(Log log) {
        this.log = log;
    }

    public Log getLog() {
        if (log == null) {
            setLog(new SystemStreamLog());
        }
        return log;
    }

    //------------------------------------------------------------------------//
    // dependency change detection

    /**
     * Master switch to look for and log changed dependencies.  If this is set to <code>true</code> and the file referenced by
     * <code>dependencyCache</code> does not exist, it will be unconditionally generated.  If the file does exist, it is
     * used to detect changes from previous builds and generate logs of those changes.  In that case,
     * <code>failOnDependencyChange = true</code> will cause the build to fail.
     */
    @Parameter(defaultValue = "false")
    private boolean checkDependencyChange;

    /**
     * (wrapper) Location of dependency cache.  This file is generated to contain known dependencies and is generally
     * located in SCM so that it may be used across separate developer builds. This is parameter is ignored unless
     * <code>checkDependencyChange</code> is set to <code>true</code>.
     */
    @Parameter(defaultValue = "${basedir}/src/main/history/dependencies.xml")
    private File dependencyCache;

    /**
     * Location of filtered dependency file.
     */
    @Parameter(defaultValue = "${basedir}/target/history/dependencies.xml", readonly = true)
    private File filteredDependencyCache;

    /**
     * Whether to fail on changed dependencies (default, <code>true</code>) or warn (<code>false</code>). This is parameter is ignored unless
     * <code>checkDependencyChange</code> is set to <code>true</code> and <code>dependencyCache</code> exists to compare
     * against.
     */
    @Parameter(defaultValue = "true")
    private boolean failOnDependencyChange;

    /**
     * Copies the contents of dependency change logs that are generated to stdout. This is parameter is ignored unless
     * <code>checkDependencyChange</code> is set to <code>true</code> and <code>dependencyCache</code> exists to compare
     * against.
     */
    @Parameter(defaultValue = "false")
    private boolean logDependencyChanges;

    /**
     * Whether to overwrite the file referenced by <code>dependencyCache</code> if it has changed.  This is parameter is
     * ignored unless <code>checkDependencyChange</code> is set to <code>true</code>, <code>failOnDependencyChange</code>
     * is set to <code>false</code> and <code>dependencyCache</code> exists to compare against.
     */
    @Parameter(defaultValue = "false")
    private boolean overwriteChangedDependencies;

    //filtering support
    /**
     * The character encoding scheme to be applied when filtering resources.
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}")
    protected String encoding;

    /**
     * Expression preceded with the String won't be interpolated
     * \${foo} will be replaced with ${foo}
     */
    @Parameter(defaultValue = "${maven.resources.escapeString}")
    protected String escapeString = "\\";

    /**
     * System properties.
     */
    @Parameter
    protected Map<String, String> systemProperties;

    private void checkChanges(Features newFeatures, ObjectFactory objectFactory) throws Exception, IOException, JAXBException, XMLStreamException {
        if (checkDependencyChange) {
            //combine all the dependencies to one feature and strip out versions
            Features features = objectFactory.createFeaturesRoot();
            features.setName(newFeatures.getName());
            Feature feature = objectFactory.createFeature();
            features.getFeature().add(feature);
            for (Feature f : newFeatures.getFeature()) {
                for (Bundle b : f.getBundle()) {
                    Bundle bundle = objectFactory.createBundle();
                    bundle.setLocation(b.getLocation());
                    feature.getBundle().add(bundle);
                }
                for (Dependency d : f.getFeature()) {
                    Dependency dependency = objectFactory.createDependency();
                    dependency.setName(d.getName());
                    feature.getFeature().add(dependency);
                }
            }

            Collections.sort(feature.getBundle(), new Comparator<Bundle>() {

                public int compare(Bundle bundle, Bundle bundle1) {
                    return bundle.getLocation().compareTo(bundle1.getLocation());
                }
            });
            Collections.sort(feature.getFeature(), new Comparator<Dependency>() {
                public int compare(Dependency dependency, Dependency dependency1) {
                    return dependency.getName().compareTo(dependency1.getName());
                }
            });

            if (dependencyCache.exists()) {
                //filter dependencies file
                filter(dependencyCache, filteredDependencyCache);
                //read dependency types, convert to dependencies, compare.
                Features oldfeatures = readFeaturesFile(filteredDependencyCache);
                Feature oldFeature = oldfeatures.getFeature().get(0);

                List<Bundle> addedBundles = new ArrayList<Bundle>(feature.getBundle());
                List<Bundle> removedBundles = new ArrayList<Bundle>();
                for (Bundle test : oldFeature.getBundle()) {
                    boolean t1 = addedBundles.contains(test);
                    int s1 = addedBundles.size();
                    boolean t2 = addedBundles.remove(test);
                    int s2 = addedBundles.size();
                    if (t1 != t2) {
                        getLog().warn("dependencies.contains: " + t1 + ", dependencies.remove(test): " + t2);
                    }
                    if (t1 == (s1 == s2)) {
                        getLog().warn("dependencies.contains: " + t1 + ", size before: " + s1 + ", size after: " + s2);
                    }
                    if (!t2) {
                        removedBundles.add(test);
                    }
                }

                List<Dependency> addedDependencys = new ArrayList<Dependency>(feature.getFeature());
                List<Dependency> removedDependencys = new ArrayList<Dependency>();
                for (Dependency test : oldFeature.getFeature()) {
                    boolean t1 = addedDependencys.contains(test);
                    int s1 = addedDependencys.size();
                    boolean t2 = addedDependencys.remove(test);
                    int s2 = addedDependencys.size();
                    if (t1 != t2) {
                        getLog().warn("dependencies.contains: " + t1 + ", dependencies.remove(test): " + t2);
                    }
                    if (t1 == (s1 == s2)) {
                        getLog().warn("dependencies.contains: " + t1 + ", size before: " + s1 + ", size after: " + s2);
                    }
                    if (!t2) {
                        removedDependencys.add(test);
                    }
                }
                if (!addedBundles.isEmpty() || !removedBundles.isEmpty() || !addedDependencys.isEmpty() || !removedDependencys.isEmpty()) {
                    saveDependencyChanges(addedBundles, removedBundles, addedDependencys, removedDependencys, objectFactory);
                    if (overwriteChangedDependencies) {
                        writeDependencies(features, dependencyCache);
                    }
                } else {
                    getLog().info(saveTreeListing());
                }

            } else {
                writeDependencies(features, dependencyCache);
            }
        }
    }

    protected void saveDependencyChanges(Collection<Bundle> addedBundles, Collection<Bundle> removedBundles, Collection<Dependency> addedDependencys, Collection<Dependency> removedDependencys, ObjectFactory objectFactory)
            throws Exception {
        File addedFile = new File(filteredDependencyCache.getParentFile(), "dependencies.added.xml");
        Features added = toFeatures(addedBundles, addedDependencys, objectFactory);
        writeDependencies(added, addedFile);

        File removedFile = new File(filteredDependencyCache.getParentFile(), "dependencies.removed.xml");
        Features removed = toFeatures(removedBundles, removedDependencys, objectFactory);
        writeDependencies(removed, removedFile);

        StringWriter out = new StringWriter();
        out.write(saveTreeListing());

        out.write("Dependencies have changed:\n");
        if (!addedBundles.isEmpty() || !addedDependencys.isEmpty()) {
            out.write("\tAdded dependencies are saved here: " + addedFile.getAbsolutePath() + "\n");
            if (logDependencyChanges) {
                JaxbUtil.marshal(added, out);
            }
        }
        if (!removedBundles.isEmpty() || !removedDependencys.isEmpty()) {
            out.write("\tRemoved dependencies are saved here: " + removedFile.getAbsolutePath() + "\n");
            if (logDependencyChanges) {
                JaxbUtil.marshal(removed, out);
            }
        }
        out.write("Delete " + dependencyCache.getAbsolutePath()
                + " if you are happy with the dependency changes.");

        if (failOnDependencyChange) {
            throw new MojoFailureException(out.toString());
        } else {
            getLog().warn(out.toString());
        }
    }

    private Features toFeatures(Collection<Bundle> addedBundles, Collection<Dependency> addedDependencys, ObjectFactory objectFactory) {
        Features features = objectFactory.createFeaturesRoot();
        Feature feature = objectFactory.createFeature();
        feature.getBundle().addAll(addedBundles);
        feature.getFeature().addAll(addedDependencys);
        features.getFeature().add(feature);
        return features;
    }


    private void writeDependencies(Features features, File file) throws JAXBException, IOException {
        file.getParentFile().mkdirs();
        if (!file.getParentFile().exists() || !file.getParentFile().isDirectory()) {
            throw new IOException("Cannot create directory at " + file.getParent());
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            JaxbUtil.marshal(features, out);
        } finally {
            out.close();
        }
    }

    protected void filter(File sourceFile, File targetFile)
            throws MojoExecutionException {
        try {

            if (StringUtils.isEmpty(encoding)) {
                getLog().warn(
                        "File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                                + ", i.e. build is platform dependent!");
            }
            targetFile.getParentFile().mkdirs();
            @SuppressWarnings("rawtypes")
            List filters = mavenFileFilter.getDefaultFilterWrappers(project, null, true, mavenSession, null);
            mavenFileFilter.copyFile(sourceFile, targetFile, true, filters, encoding, true);
        } catch (MavenFilteringException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    protected String saveTreeListing() throws IOException {
        File treeListFile = new File(filteredDependencyCache.getParentFile(), "treeListing.txt");
        OutputStream os = new FileOutputStream(treeListFile);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
        try {
            writer.write(treeListing);
        } finally {
            writer.close();
        }
        return "\tTree listing is saved here: " + treeListFile.getAbsolutePath() + "\n";
    }

}
