package org.codehaus.mojo.properties;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.cli.CommandLineUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;

/**
 * The read-project-properties goal reads property files and URLs and stores the properties as project properties. It serves as an alternate
 * to specifying properties in pom.xml. It is especially useful when making properties defined in a runtime resource available at build
 * time.
 *
 * @author <a href="mailto:zarars@gmail.com">Zarar Siddiqi</a>
 * @author <a href="mailto:Krystian.Nowak@gmail.com">Krystian Nowak</a>
 * @version $Id$
 */
@Mojo(name = "read-project-properties", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = true)
public class ReadPropertiesMojo extends AbstractMojo {

    private static final ResourceType[] SUPPORTED_RESOURCE_TYPES = {
            ResourceType.PROPERTIES, ResourceType.YAML
    };

    /**
     * Used for resolving property placeholders.
     */
    private final PropertyResolver resolver = new PropertyResolver();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The properties files that will be used when reading properties.
     */
    @Parameter
    private File[] files = new File[0];

    /**
     * The URLs that will be used when reading properties. These may be non-standard URLs of the form <code>
     * classpath:com/company/resource.properties</code>. Note that the type is not <code>URL</code> for this reason and therefore will be
     * explicitly checked by this Mojo.
     */
    @Parameter
    private String[] urls = new String[0];

    /**
     * If the plugin should be quiet if any of the files was not found.
     */
    @Parameter(defaultValue = "false")
    private boolean quiet;

    /**
     * Prefix that will be added before name of each property. Can be useful for separating properties with same name from different files.
     */
    @Parameter
    private String keyPrefix;

    private static ResourceType identifyResourceType(final String fileName) throws MojoExecutionException {
        final ResourceType resourceType = ResourceType.getByFileName(fileName);
        if (resourceType == null) {
            throw new MojoExecutionException("Cannot find a resource type for " + fileName);
        }

        return resourceType;
    }

    public void setKeyPrefix(final String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /**
     * {@inheritDoc}
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkParameters();

        loadFiles();

        loadUrls();

        resolveProperties();
    }

    private void checkParameters() throws MojoExecutionException {
        if (files.length > 0 && urls.length > 0) {
            throw new MojoExecutionException("Set files or URLs but not both - otherwise " + "no order of precedence can be guaranteed");
        }

        for (final File file : files) {
            if (!file.isFile() && !quiet) {
                throw new MojoExecutionException(String.format("File expected, but %s is not a file", file.getPath()));
            }

            if (!endsWithExtension(file.getAbsolutePath()) && !quiet) {
                throw new MojoExecutionException(String.format(
                        "File name must ends with '.properties', '.yml' or '.yaml', while file '%s' was found", file.getName()));
            }
        }

        for (final String url : urls) {
            if (!endsWithExtension(url)) {
                throw new MojoExecutionException(String.format(
                        "Url must ends with '.properties', '.yml' or '.yaml', while url '%s' was found", url));
            }
        }
    }

    private void loadFiles() throws MojoExecutionException {
        for (final File file : files) {
            load(new FileResource(file));
        }
    }

    private void loadUrls() throws MojoExecutionException {
        for (final String url : urls) {
            load(new UrlResource(url));
        }
    }

    private void load(final Resource resource) throws MojoExecutionException {
        if (resource.canBeOpened()) {
            loadProperties(resource);
        } else {
            missing(resource);
        }
    }

    private void loadProperties(final Resource resource) throws MojoExecutionException {
        try {
            getLog().debug("Loading properties from " + resource);

            final ResourceType resourceType = resource.getResourceType();
            final InputStream stream = resource.getInputStream();
            try {
                final Properties properties;
                switch (resourceType) {
                case PROPERTIES:
                    properties = new Properties();
                    properties.load(stream);
                    break;

                case YAML:
                    properties = YamlToPropertiesConverter.convertToProperties(stream);
                    break;

                default:
                    throw new MojoExecutionException("Error reading properties from " + resource, new UnsupportedOperationException(String
                            .format("Resource Type %s is unknown", resourceType)));
                }

                final Properties projectProperties = project.getProperties();
                for (final String key : properties.stringPropertyNames()) {
                    projectProperties.put(keyPrefix != null ? keyPrefix + key : key, properties.get(key));
                }
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading properties from " + resource, e);
        }
    }

    private void missing(final Resource resource) throws MojoExecutionException {
        if (quiet) {
            getLog().info("Quiet processing - ignoring properties cannot be loaded from " + resource);
        } else {
            throw new MojoExecutionException("Properties could not be loaded from " + resource);
        }
    }

    private void resolveProperties() throws MojoExecutionException, MojoFailureException {
        final Properties environment = loadSystemEnvironmentPropertiesWhenDefined();
        final Properties projectProperties = project.getProperties();

        for (final Enumeration<?> n = projectProperties.propertyNames(); n.hasMoreElements();) {
            final String k = (String) n.nextElement();
            projectProperties.setProperty(k, getPropertyValue(k, projectProperties, environment));
        }
    }

    private Properties loadSystemEnvironmentPropertiesWhenDefined() throws MojoExecutionException {
        final Properties projectProperties = project.getProperties();

        boolean useEnvVariables = false;
        for (final Enumeration<?> n = projectProperties.propertyNames(); n.hasMoreElements();) {
            final String k = (String) n.nextElement();
            final String p = (String) projectProperties.get(k);
            if (p.indexOf("${env.") != -1) {
                useEnvVariables = true;
                break;
            }
        }

        if (useEnvVariables) {
            try {
                return getSystemEnvVars();
            } catch (IOException e) {
                throw new MojoExecutionException("Error getting system environment variables: ", e);
            }
        } else {
            return null;
        }
    }

    private String getPropertyValue(final String k, final Properties p, final Properties environment) throws MojoFailureException {
        try {
            return resolver.getPropertyValue(k, p, environment);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
    }

    /**
     * Override-able for test purposes.
     *
     * @return The shell environment variables, can be empty but never <code>null</code>.
     * @throws IOException
     *             If the environment variables could not be queried from the shell.
     */
    Properties getSystemEnvVars() throws IOException {
        return CommandLineUtils.getSystemEnvVars();
    }

    /**
     * Default scope for test access.
     *
     * @param quiet
     *            Set to <code>true</code> if missing files can be skipped.
     */
    void setQuiet(final boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Default scope for test access.
     *
     * @param project
     *            The test project.
     */
    void setProject(final MavenProject project) {
        this.project = project;
    }

    private boolean endsWithExtension(final String text) {
        for (final String extension : ResourceType.allFileExtensions(SUPPORTED_RESOURCE_TYPES)) {
            if (text.toLowerCase().endsWith(extension)) {
                return true;
            }
        }

        return false;
    }

    private abstract static class Resource {

        private final ResourceType resourceType;

        private InputStream stream;

        protected Resource(final ResourceType resourceType) {
            this.resourceType = resourceType;
        }

        public abstract boolean canBeOpened();

        protected abstract InputStream openStream() throws IOException;

        public InputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = openStream();
            }

            return stream;
        }

        public ResourceType getResourceType() {
            return resourceType;
        }
    }

    private static class FileResource extends Resource {

        private final File file;

        public FileResource(final File file) throws MojoExecutionException {
            super(identifyResourceType(file.getName()));
            this.file = file;
        }

        public boolean canBeOpened() {
            return file.exists();
        }

        protected InputStream openStream() throws IOException {
            return new BufferedInputStream(new FileInputStream(file));
        }

        public String toString() {
            return "File: " + file;
        }

    }

    private static class UrlResource extends Resource {

        private static final String CLASSPATH_PREFIX = "classpath:";

        private static final String SLASH_PREFIX = "/";

        private final URL url;

        private boolean isMissingClasspathResource = false;

        private String classpathUrl;

        public UrlResource(final String url) throws MojoExecutionException {
            super(identifyResourceType(url));
            if (url.startsWith(CLASSPATH_PREFIX)) {
                String resource = url.substring(CLASSPATH_PREFIX.length(), url.length());
                if (resource.startsWith(SLASH_PREFIX)) {
                    resource = resource.substring(1, resource.length());
                }

                this.url = getClass().getClassLoader().getResource(resource);
                if (this.url == null) {
                    isMissingClasspathResource = true;
                    classpathUrl = url;
                }
            } else {
                try {
                    this.url = new URL(url);
                } catch (MalformedURLException e) {
                    throw new MojoExecutionException(String.format("Badly formed URL %s - %s", url, e.getMessage()));
                }
            }
        }

        public boolean canBeOpened() {
            if (isMissingClasspathResource) {
                return false;
            }

            try {
                openStream();
            } catch (IOException e) {
                return false;
            }

            return true;
        }

        protected InputStream openStream() throws IOException {
            return new BufferedInputStream(url.openStream());
        }

        public String toString() {
            if (!isMissingClasspathResource) {
                return "URL " + url.toString();
            }

            return classpathUrl;
        }
    }

}
