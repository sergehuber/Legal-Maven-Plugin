package org.jahia.tools.maven.plugins;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Created by loom on 16.02.16.
 */
@XmlRootElement(name="license")
public class KnownLicense {

    private String id;

    private String name;
    private Set<String> aliases = new TreeSet<>();

    private String version;
    private boolean viral;

    private List<TextVariant> textVariants = new ArrayList<>();

    private String textToUse;

    KnownLicense() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    public void setAliases(Set<String> aliases) {
        this.aliases = aliases;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isViral() {
        return viral;
    }

    public void setViral(boolean viral) {
        this.viral = viral;
    }

    public List<TextVariant> getTextVariants() {
        return textVariants;
    }

    public void setTextVariants(List<TextVariant> textVariants) {
        this.textVariants = textVariants;
    }

    public String getTextToUse() {
        return textToUse;
    }

    public void setTextToUse(String textToUse) {
        this.textToUse = textToUse;
    }
}
