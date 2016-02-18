package org.jahia.tools.maven.plugins;

import javax.xml.bind.annotation.XmlTransient;
import java.util.regex.Pattern;

/**
 * Created by loom on 18.02.16.
 */
public class TextVariant {
    private String id;
    private boolean defaultVariant = false;
    private String text;
    private Pattern compiledTextPattern;

    public TextVariant() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isDefaultVariant() {
        return defaultVariant;
    }

    public void setDefaultVariant(boolean defaultVariant) {
        this.defaultVariant = defaultVariant;
    }

    @XmlTransient
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        this.compiledTextPattern = Pattern.compile(text);
    }

    @XmlTransient
    public Pattern getCompiledTextPattern() {
        return compiledTextPattern;
    }
}
