package org.jahia.tools.maven.plugins;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by loom on 16.02.16.
 */
@XmlRootElement(name="known-licenses")
public class KnownLicenses {

    public List<KnownLicense> licenses = new ArrayList<>();

}
