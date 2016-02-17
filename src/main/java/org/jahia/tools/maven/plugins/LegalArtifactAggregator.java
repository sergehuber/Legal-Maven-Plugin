package org.jahia.tools.maven.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.glassfish.jersey.client.ClientProperties;

import javax.net.ssl.*;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by loom on 29.01.16.
 */
class LegalArtifactAggregator {

    private static final String START_INDENT = "";
    private static final String INDENT_STEP = "  ";
    private static final int EDIT_DISTANCE_THRESHOLD = 1000;
    private final File rootDirectory;

    private static Map<String,Client> clients = new TreeMap<String,Client>();
    public static final String MAVEN_SEARCH_HOST_URL = "http://search.maven.org";
    public static final String NETWORK_ERROR_PREFIX = "NETWORK ERROR: ";

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession repositorySystemSession;
    private final List<RemoteRepository> remoteRepositories;

    private final Map<License, List<String>> licensesFound = new HashMap<>(101);
    private final List<String> duplicatedLicenses = new LinkedList<>();
    private final List<String> missingLicenses = new LinkedList<>();


    private final SortedMap<String, Set<Notice>> projectToNotice = new TreeMap<>();
    private final List<String> duplicatedNotices = new LinkedList<>();
    private final List<String> missingNotices = new LinkedList<>();

    private final boolean verbose;
    private final boolean outputDiagnostics;
    private final boolean updateKnownLicenses = true;

    private Set<String> forbiddenKeyWords = new HashSet<>();

    KnownLicenses knownLicenses = null;
    ObjectMapper mapper = new ObjectMapper();

    LegalArtifactAggregator(File rootDirectory, RepositorySystem repositorySystem, RepositorySystemSession repositorySystemSession, List<RemoteRepository> remoteRepositories,
                            boolean verbose, boolean outputDiagnostics) {
        this.rootDirectory = rootDirectory;
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = repositorySystemSession;
        this.remoteRepositories = remoteRepositories;
        this.verbose = verbose;
        this.outputDiagnostics = outputDiagnostics;
        forbiddenKeyWords.add("gpl");

        JaxbAnnotationModule jaxbAnnotationModule = new JaxbAnnotationModule();
        // configure as necessary
        mapper.registerModule(jaxbAnnotationModule);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        URL knownLicensesJSONURL = this.getClass().getClassLoader().getResource("known-licenses.json");
        if (knownLicensesJSONURL != null) {
            try {
                knownLicenses = mapper.readValue(knownLicensesJSONURL, KnownLicenses.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void execute() {
        Collection<File> jarFiles = FileUtils.listFiles(rootDirectory, new String[]{"jar"}, true);
        for (File jarFile : jarFiles) {
            try {
                processJarFile(jarFile, true, 0, true, true);
            } catch (IOException e) {
                output(START_INDENT, "Error handling JAR " + jarFile.getPath() + ". This file will be ignored.", true, true);
            }
        }

        if (verbose || outputDiagnostics) {
            outputDiagnostics(false);
            outputDiagnostics(true);
        }

        output(START_INDENT, "Processed projects: ");
        List<String> allNoticeLines = new LinkedList<>();
        for (Map.Entry<String, Set<Notice>> entry : projectToNotice.entrySet()) {
            final String project = entry.getKey();
            output(START_INDENT, project);
            final Set<Notice> notices = entry.getValue();
            if (!notices.isEmpty()) {
                allNoticeLines.add("Notice for " + project);
                allNoticeLines.add("---------------------------------------------------------------------------------------------------");
                for (Notice notice : notices) {
                    allNoticeLines.add(notice.toString());
                    allNoticeLines.add("\n");
                }
            }
        }

        FileWriter writer = null;
        try {
            File aggregatedNoticeFile = new File(rootDirectory, "NOTICE-aggregated");
            writer = new FileWriter(aggregatedNoticeFile);
            for (String noticeLine : allNoticeLines) {
                writer.append(noticeLine);
                writer.append("\n");
            }

            output(START_INDENT, "Aggregated NOTICE created at " + aggregatedNoticeFile.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        IOUtils.closeQuietly(writer);

        try {
            File aggregatedLicenseFile = new File(rootDirectory, "LICENSE-aggregated");
            writer = new FileWriter(aggregatedLicenseFile);
            for (Map.Entry<License, List<String>> license : licensesFound.entrySet()) {
                output(START_INDENT, "Adding license " + license.getKey());
                List<String> locations = license.getValue();
                writer.append("License for:\n");
                for (String location : locations) {
                    writer.append("  " + location + "\n");
                }
                writer.append(license.getKey().toString());
                writer.append("\n\n\n");
            }

            output(START_INDENT, "Aggregated LICENSE created at " + aggregatedLicenseFile.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        IOUtils.closeQuietly(writer);

        if (updateKnownLicenses) {
            for (License license : licensesFound.keySet()) {
                if (license.getKnownLicenses() == null || license.getKnownLicenses().size() == 0) {
                    KnownLicense knownLicense = new KnownLicense();
                    knownLicense.setName("Unknown");
                    List<String> textVariants = new ArrayList<>();
                    textVariants.add(Pattern.quote(license.getText()));
                    knownLicense.setTextVariants(textVariants);
                    knownLicense.setViral(license.getText().toLowerCase().contains("gpl"));
                    knownLicenses.licenses.add(knownLicense);
                }
            }
            File knownLicensesFile = new File(rootDirectory, "known-licenses.json");
            try {
                mapper.writeValue(knownLicensesFile, knownLicenses);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void outputDiagnostics(boolean forLicenses) {
        final String typeName = forLicenses ? "licenses" : "notices";

        Set seen = forLicenses ? licensesFound.entrySet() : projectToNotice.entrySet();
        List<String> duplicated = forLicenses ? duplicatedLicenses : duplicatedNotices;
        List<String> missingItems = forLicenses ? missingLicenses : missingNotices;

        output(START_INDENT, "Found " + seen.size() + " unique " + typeName, false, true);

        if (!duplicated.isEmpty()) {
            System.out.println("Omitted duplicated " + typeName + " for the following " + duplicated.size() + " JAR files:");
            for (String duplicate : duplicated) {
                output(INDENT_STEP, duplicate, false, true);
            }
        }

        if (!missingItems.isEmpty()) {
            output(START_INDENT, "Couldn't find any " + typeName + " in the following " + missingItems.size() +
                    " JAR files or their sources. Please check them manually:", false, true);
            for (String missing : missingItems) {
                output(INDENT_STEP, missing, false, true);
            }
        }
    }

    private void processJarFile(File jarFile, boolean processMavenPom, int level, boolean lookForNotice, boolean lookForLicense) throws IOException {
        // if we don't need to find either a license or notice, don't process the jar at all
        if (!lookForLicense && !lookForNotice) {
            return;
        }

        JarFile realJarFile = new JarFile(jarFile);
        Enumeration<JarEntry> jarEntries = realJarFile.entries();
        String pomFilePath = null;

        final String indent = getIndent(level);

        output(indent, "Processing " + jarFile.getName(), false, true);
        Notice notice;
        Set<JarMetadata> embeddedJars = new HashSet<>(7);
        while (jarEntries.hasMoreElements()) {
            JarEntry curJarEntry = jarEntries.nextElement();

            if (!curJarEntry.isDirectory()) {
                final String fileName = curJarEntry.getName();
                if (lookForNotice && isNotice(fileName, jarFile)) {
                    InputStream noticeInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> noticeLines = IOUtils.readLines(noticeInputStream);
                    notice = new Notice(noticeLines);

                    // compute project name
                    final String jarFileName = getJarFileName(jarFile.getName());
                    final JarMetadata jarMetadata = new JarMetadata(jarFileName);

                    final String project = jarMetadata.getProject() != null ? jarMetadata.getProject() : JarMetadata.getDashSeparatedProjectName(jarFileName);
                    Set<Notice> notices = projectToNotice.get(project);
                    if (notices == null) {
                        notices = new HashSet<>(17);
                        notices.add(notice);
                        output(indent, "Found first notice " + fileName);
                        projectToNotice.put(project, notices);
                    } else if (!notices.contains(notice)) {
                        output(indent, "Found additional notice " + fileName);
                        notices.add(notice);
                    } else {
                        output(indent, "Duplicated notice");
                        duplicatedNotices.add(jarFile.getPath());
                    }

                    lookForNotice = false;

                    IOUtils.closeQuietly(noticeInputStream);
                } else if (processMavenPom && fileName.endsWith("pom.xml")) {
                    // remember pom file path in case we need it
                    pomFilePath = curJarEntry.getName();
                } else if (lookForLicense && isLicense(fileName, jarFile)) {
                    InputStream licenseInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> licenseLines = IOUtils.readLines(licenseInputStream);

                    License license = new License(licenseLines);
                    findKnownLicenses(license);
                    List<String> locations = licensesFound.get(license);
                    if (locations != null) {
                        output(indent, "Licenses found in " + fileName);
                        locations.add(jarFile.getPath());
                        licensesFound.put(license, locations);
                        duplicatedLicenses.add(jarFile.getPath());
                    } else {
                        locations = new ArrayList<String>();
                        locations.add(jarFile.getPath());
                        output(indent, "Found new licenses in " + fileName);
                        licensesFound.put(license, locations);
                    }

                    lookForLicense = false;

                    IOUtils.closeQuietly(licenseInputStream);

                } else if (fileName.endsWith(".jar")) {
                    final JarMetadata jarMetadata = new JarMetadata(getJarFileName(fileName));

                    if (jarMetadata != null) {
                        embeddedJars.add(jarMetadata);
                    }
                }
            }
        }

        if (processMavenPom) {
            if (pomFilePath == null) {
                output(indent, "No POM found");
            } else {
                processJarPOM(realJarFile, pomFilePath, lookForNotice, lookForLicense, embeddedJars, level + 1);
            }
        }

        if (lookForLicense || lookForNotice) {
            if (lookForLicense) {
                output(indent, "No license found");
            }
            if (lookForNotice) {
                output(indent, "No notice found");
            }

            if (pomFilePath == null && lookForLicense && lookForNotice) {
                final JarMetadata jarMetadata = new JarMetadata(FilenameUtils.getBaseName(jarFile.getName()));
                List<Artifact> mavenCentralArtifacts = findArtifactInMavenCentral(jarMetadata.getName(), jarMetadata.getVersion(), jarMetadata.getClassifier());
                if (mavenCentralArtifacts != null && mavenCentralArtifacts.size() == 1) {
                    Artifact mavenCentralArtifact = mavenCentralArtifacts.get(0);
                    Artifact resolvedArtifact = resolveArtifact(mavenCentralArtifact, level);
                    if (resolvedArtifact != null) {
                        // we have a copy of the local artifact, let's request the sources for it.
                        if (!"sources".equals(jarMetadata.getClassifier())) {
                            final Artifact artifact = new DefaultArtifact(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(), "sources", "jar", resolvedArtifact.getVersion());
                            File sourceJar = getArtifactFile(artifact, level);
                            if (sourceJar != null && sourceJar.exists()) {
                                processJarFile(sourceJar, false, level+1, lookForNotice, lookForLicense);
                            }
                        } else {
                            // we are already processing a sources artifact, we need to load the pom artifact to extract information from there
                            final Artifact artifact = new DefaultArtifact(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(), null, "pom", resolvedArtifact.getVersion());
                            File artifactPom = getArtifactFile(artifact, level);
                            if (artifactPom != null && artifactPom.exists()) {
                                processPOM(lookForNotice, lookForLicense, embeddedJars, level + 1, new FileInputStream(artifactPom), false);
                            }
                        }
                    } else {
                        output(indent, "===>  Couldn't resolve artifact "+mavenCentralArtifact+" in Maven Central. Please resolve license and notice files manually!", false, true);
                    }
                } else {
                    output(indent, "===>  Couldn't find nor POM, license or notice. Please check manually!", false, true);
                }
            }
        }

        realJarFile.close();
    }

    private String getIndent(int level) {
        String indent = START_INDENT;
        int i = level;
        while (i-- != 0) {
            indent += INDENT_STEP;
        }
        return indent;
    }

    private String getJarFileName(String fileName) {
        final int lastSlash = fileName.lastIndexOf('/');
        fileName = lastSlash > 0 ? fileName.substring(lastSlash + 1) : fileName;
        final int endVersion = fileName.lastIndexOf('.');
        return fileName.substring(0, endVersion);
    }

    private void output(String indent, String message) {
        output(indent, message, false, false);
    }

    private void output(String indent, String message, boolean error, boolean force) {
        PrintStream out = error ? System.err : System.out;
        if (force || error || verbose) {
            out.println(indent + message);
        }
    }

    private boolean isNotice(String fileName, File jarFile) {
        boolean isNotice = fileName.endsWith("NOTICE");

        if (!isNotice) {
            String lowerCase = fileName.toLowerCase();
            // retrieve last part of name
            String separator = lowerCase.contains("\\") ? "\\" : "/";
            final String[] split = lowerCase.split(separator);
            if (split.length > 0) {
                String potential = split[split.length - 1];
                isNotice = potential.startsWith("notice") && !potential.endsWith(".class");

                if (verbose) {
                    if (!isNotice && lowerCase.contains("notice")) {
                        System.out.println("Potential notice file " + fileName + " in JAR " + jarFile.getName()
                                + " was NOT handled. You might want to check manually.");
                    }
                }
            }

        }

        return isNotice;
    }

    private boolean isLicense(String fileName, File jarFile) {
        boolean isLicense = fileName.endsWith("LICENSE");

        if (!isLicense) {
            String lowerCase = fileName.toLowerCase();
            // retrieve last part of name
            String separator = lowerCase.contains("\\") ? "\\" : "/";
            final String[] split = lowerCase.split(separator);
            if (split.length > 0) {
                String potential = split[split.length - 1];
                isLicense = potential.startsWith("license") && !potential.endsWith(".class");

                if (verbose) {
                    if (!isLicense && lowerCase.contains("license")) {
                        System.out.println("Potential license file " + fileName + " in JAR " + jarFile.getName()
                                + " was NOT handled. You might want to check manually.");
                    }
                }
            }
        }

        return isLicense;
    }

    private void processJarPOM(JarFile realJarFile, String pomFilePath, boolean lookForNotice, boolean lookForLicense, Set<JarMetadata> embeddedJarNames, int level) throws
            IOException {
        // if we're not looking for notice or license and don't have embedded jars, don't process at all
        if (embeddedJarNames.isEmpty() && !lookForNotice && !lookForLicense) {
            return;
        }

        JarEntry pomJarEntry = new JarEntry(pomFilePath);
        InputStream pomInputStream = realJarFile.getInputStream(pomJarEntry);

        processPOM(lookForNotice, lookForLicense, embeddedJarNames, level, pomInputStream, true);

    }

    private void processPOM(boolean lookForNotice, boolean lookForLicense, Set<JarMetadata> embeddedJarNames, int level, InputStream pomInputStream, boolean retrieveSourceJar) throws IOException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try {
            final Model model = reader.read(pomInputStream);
            final Parent parent = model.getParent();
            String parentGroupId = null;
            String parentVersion = null;
            if (parent != null) {
                parentGroupId = parent.getGroupId();
                parentVersion = parent.getVersion();
            }

            String scmConnection = null;
            if (model.getScm() != null) {
                scmConnection = model.getScm().getDeveloperConnection();
                if (scmConnection == null) {
                    model.getScm().getConnection();
                }
                if (scmConnection == null) {
                    // @todo let's try to resolve in the parent hierarcy
                }
            }

            if (!embeddedJarNames.isEmpty()) {
                final List<Dependency> dependencies = model.getDependencies();
                Map<String, Dependency> artifactToDep = new HashMap<String, Dependency>(dependencies.size());
                for (Dependency dependency : dependencies) {
                    artifactToDep.put(dependency.getArtifactId(), dependency);
                }

                for (JarMetadata jarName : embeddedJarNames) {
                    final Dependency dependency = artifactToDep.get(jarName.name);
                    if (dependency != null) {
                        File jar = getArtifactFile(new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), null, "jar", jarName.version), level);
                        if (jar != null && jar.exists()) {
                            processJarFile(jar, true, level, true, true);
                        }
                    }
                }
            }

            if (retrieveSourceJar) {
                final String groupId = model.getGroupId() != null ? model.getGroupId() : parentGroupId;
                final String version = model.getVersion() != null ? model.getVersion() : parentVersion;
                final Artifact artifact = new DefaultArtifact(groupId, model.getArtifactId(), "sources", "jar", version);

                File sourceJar = getArtifactFile(artifact, level);
                if (sourceJar != null && sourceJar.exists()) {
                    processJarFile(sourceJar, false, level, lookForNotice, lookForLicense);
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private Artifact resolveArtifact(Artifact artifact, int level) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepositories);

        ArtifactResult artifactResult;
        try {
            artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, request);
            return artifactResult.getArtifact();
        } catch (ArtifactResolutionException e) {
            output(getIndent(level), "Couldn't find artifact " + artifact + " : " + e.getMessage(), true, true);
        }
        return null;
    }

    private File getArtifactFile(Artifact artifact, int level) {
        Artifact resolvedArtifact = resolveArtifact(artifact, level);
        if (resolvedArtifact == null) {
            return null;
        }
        return resolvedArtifact.getFile();
    }

    private static Client getRestClient(String targetUrl) {

        if (clients.containsKey(targetUrl)) {
            return clients.get(targetUrl);
        }

        Client client = null;
        if (targetUrl != null) {
            if (targetUrl.startsWith("https://")) {
                try {
                    // Create a trust manager that does not validate certificate chains
                    TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
                    };
                    // Create all-trusting host name verifier
                    HostnameVerifier allHostsValid = new HostnameVerifier() {
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    };
                    SSLContext sslContext = SSLContext.getInstance("SSL");
                    sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                    client = ClientBuilder.newBuilder().
                            sslContext(sslContext).
                            hostnameVerifier(allHostsValid).build();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                } catch (KeyManagementException e) {
                    e.printStackTrace();
                }
            } else {
                client = ClientBuilder.newClient();

            }
        }
        if (client == null) {
            return null;
        }

        client.property(ClientProperties.CONNECT_TIMEOUT, 1000);
        client.property(ClientProperties.READ_TIMEOUT,    3000);
        /*
        HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(contextServerSettings.getContextServerUsername(), contextServerSettings.getContextServerPassword());
        client.register(feature);
        */
        clients.put(targetUrl, client);
        return client;
    }

    /**
     * This method will use the public REST API at search.maven.org to search for Maven dependencies that contain
     * a package using an URL such as :
     *
     * http://search.maven.org/solrsearch/select?q=fc:%22com.mchange.v2.c3p0%22&rows=20&wt=json
     *
     * @param packageName
     */
    public static List<String> findPackageInMavenCentral(String packageName) {
        List<String> artifactResults = new ArrayList<String>();
        Client client = getRestClient(MAVEN_SEARCH_HOST_URL);

        WebTarget target = client.target(MAVEN_SEARCH_HOST_URL).path("solrsearch/select")
                .queryParam("q", "fc:\"" + packageName + "\"")
                .queryParam("rows", "5")
                .queryParam("wt", "json");

        Invocation.Builder invocationBuilder =
                target.request(MediaType.APPLICATION_JSON_TYPE);

        Map<String, Object> searchResults = null;
        try {
            Response response = invocationBuilder.get();
            searchResults= (Map<String, Object>) response.readEntity(Map.class);
        } catch (ProcessingException pe) {
            artifactResults.add(NETWORK_ERROR_PREFIX + pe.getMessage());
        }

        if (searchResults != null) {
            Map<String,Object> searchResponse = (Map<String,Object>) searchResults.get("response");
            Integer searchResultCount = (Integer) searchResponse.get("numFound");
            List<Map<String,Object>> docs = (List<Map<String,Object>>) searchResponse.get("docs");
            for (Map<String,Object> doc : docs) {
                String artifactId = (String) doc.get("id");
                artifactResults.add(artifactId);
            }
        }

        return artifactResults;
    }

    /**
     * This method will use the public REST API at search.maven.org to search for Maven dependencies that match
     * the artifactId and version ID as in the following example
     *
     * http://search.maven.org/solrsearch/select?q=g:%22com.google.inject%22%20AND%20a:%22guice%22%20AND%20v:%223.0%22%20AND%20l:%22javadoc%22%20AND%20p:%22jar%22&rows=20&wt=json
     *
     * @param artifactId
     * @param version
     * @return
     */
    public List<Artifact> findArtifactInMavenCentral(String artifactId, String version, String classifier) {
        List<Artifact> artifactResults = new ArrayList<Artifact>();
        Client client = getRestClient(MAVEN_SEARCH_HOST_URL);

        StringBuilder query = new StringBuilder();
        if (artifactId != null) {
            query.append("a:\"" + artifactId + "\"");
        }
        if (version != null) {
            query.append(" AND v:\"" + version + "\"");
        }
        if (classifier != null) {
            query.append(" AND l:\"" + classifier + "\"");
        }

        WebTarget target = client.target(MAVEN_SEARCH_HOST_URL).path("solrsearch/select")
                .queryParam("q", query.toString())
                .queryParam("rows", "5")
                .queryParam("wt", "json");

        Invocation.Builder invocationBuilder =
                target.request(MediaType.APPLICATION_JSON_TYPE);

        Map<String, Object> searchResults = null;
        try {
            Response response = invocationBuilder.get();
            searchResults= (Map<String, Object>) response.readEntity(Map.class);
        } catch (ProcessingException pe) {
            pe.printStackTrace();
        }

        if (searchResults != null) {
            Map<String,Object> searchResponse = (Map<String,Object>) searchResults.get("response");
            Integer searchResultCount = (Integer) searchResponse.get("numFound");
            List<Map<String,Object>> docs = (List<Map<String,Object>>) searchResponse.get("docs");
            for (Map<String,Object> doc : docs) {
                String foundId = (String) doc.get("id");
                artifactResults.add(new DefaultArtifact(foundId));
            }
        }

        return artifactResults;
    }

    /**
     * Find the closest matching license using a LevenshteinDistance edit distance algorithm because the two license
     * texts. If the edit distance is larger than the EDIT_DISTANCE_THRESHOLD it is possible that no license matches,
     * which is what we want if we are actually not matching a real license.
     * @param license the license we want to match against the known licenses.
     * @return
     */
    public KnownLicense findClosestMatchingKnownLicense(License license) {
        KnownLicense closestMatchingKnownLicense = null;
        int smallestEditDistance = Integer.MAX_VALUE;
        for (KnownLicense knownLicense : knownLicenses.licenses) {
            for (String textVariant : knownLicense.getTextVariants()) {
                int editDistance = StringUtils.getLevenshteinDistance(textVariant, license.getText(), EDIT_DISTANCE_THRESHOLD);
                if (editDistance >= 0 && editDistance < smallestEditDistance) {
                    smallestEditDistance = editDistance;
                    closestMatchingKnownLicense = knownLicense;
                }
            }
        }
        return closestMatchingKnownLicense;
    }

    public void findKnownLicenses(License license) {
        List<KnownLicense> foundLicenses = new ArrayList<>();
        String licenseText = license.getText();
        boolean licenseFound = false;
        if (knownLicenses.licenses == null) {
            return;
        }
        do {
            licenseFound = false;
            for (KnownLicense knownLicense : knownLicenses.licenses) {
                for (Pattern textVariantPattern : knownLicense.getTextVariantPatterns()) {
                    Matcher textVariantMatcher = textVariantPattern.matcher(licenseText);
                    if (textVariantMatcher.find()) {
                        foundLicenses.add(knownLicense);
                        licenseText = licenseText.substring(textVariantMatcher.end());
                        licenseFound = true;
                    }
                }
                if (licenseFound) {
                    break;
                }
            }
        } while (licenseFound);
        license.setKnownLicenses(foundLicenses);
        license.setAdditionalLicenseText(licenseText);
    }
}
