package org.jahia.tools.maven.plugins;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by loom on 16.02.16.
 */
@XmlRootElement(name="known-licenses")
public class KnownLicenses {

    private Map<String,KnownLicense> licenses = new LinkedHashMap<>();

    public KnownLicenses() {
    }

    public Map<String, KnownLicense> getLicenses() {
        return licenses;
    }

    public void setLicenses(Map<String, KnownLicense> licenses) {
        this.licenses = licenses;
    }

}
