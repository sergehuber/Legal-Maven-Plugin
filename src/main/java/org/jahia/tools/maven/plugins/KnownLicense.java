package org.jahia.tools.maven.plugins;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Created by loom on 16.02.16.
 */
@XmlRootElement(name="license")
public class KnownLicense {

    private String name;

    private List<String> aliases;

    private String version;
    private boolean viral;

    private List<String> textVariants;

    private List<Pattern> textVariantPatterns = new ArrayList<>();

    KnownLicense() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @XmlElementWrapper(name="aliases")
    @XmlElement(name="alias")
    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
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

    public List<String> getTextVariants() {
        return textVariants;
    }

    public void setTextVariants(List<String> textVariants) {
        this.textVariants = textVariants;
        if (textVariants != null && textVariants.size() > 0) {
            textVariantPatterns.clear();
            for (String textVariant : textVariants) {
                textVariantPatterns.add(Pattern.compile(textVariant));
            }
        }
    }

    @XmlTransient
    public List<Pattern> getTextVariantPatterns() {
        return textVariantPatterns;
    }
}
