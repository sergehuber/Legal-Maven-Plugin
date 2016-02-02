package org.jahia.tools.maven.plugins;

import org.eclipse.aether.artifact.Artifact;

/**
 * An artifact class that contains a parent and a GAV string
 */
public class LegalArtifact implements Comparable<LegalArtifact> {

    String artifactGAV;

    Artifact artifact;
    Artifact parentArtifact;

    public LegalArtifact(String artifactGAV, Artifact artifact, Artifact parentArtifact) {
        this.artifactGAV = artifactGAV;
        this.artifact = artifact;
        this.parentArtifact = parentArtifact;
    }

    public String getArtifactGAV() {
        return artifactGAV;
    }

    public Artifact getArtifact() {
        return artifact;
    }

    public Artifact getParentArtifact() {
        return parentArtifact;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LegalArtifact that = (LegalArtifact) o;

        return artifactGAV.equals(that.artifactGAV);

    }

    @Override
    public int hashCode() {
        return artifactGAV.hashCode();
    }


    public int compareTo(LegalArtifact o) {
        return artifactGAV.compareTo(o.artifactGAV);
    }
}
