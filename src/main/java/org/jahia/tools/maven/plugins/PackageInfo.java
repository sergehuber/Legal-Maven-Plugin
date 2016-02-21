package org.jahia.tools.maven.plugins;

/**
 * Created by loom on 21.02.16.
 */
public class PackageInfo implements Comparable<PackageInfo> {

    String jarPath;
    String packageName;
    String licenseKey;
    String version;
    int copyrightStartYear;
    int copyrightEndYear;
    String copyrightOwner;

    public PackageInfo(String jarPath, String packageName) {
        this.jarPath = jarPath;
        this.packageName = packageName;
    }

    public String getJarPath() {
        return jarPath;
    }

    public void setJarPath(String jarPath) {
        this.jarPath = jarPath;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getCopyrightStartYear() {
        return copyrightStartYear;
    }

    public void setCopyrightStartYear(int copyrightStartYear) {
        this.copyrightStartYear = copyrightStartYear;
    }

    public int getCopyrightEndYear() {
        return copyrightEndYear;
    }

    public void setCopyrightEndYear(int copyrightEndYear) {
        this.copyrightEndYear = copyrightEndYear;
    }

    public String getCopyrightOwner() {
        return copyrightOwner;
    }

    public void setCopyrightOwner(String copyrightOwner) {
        this.copyrightOwner = copyrightOwner;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PackageInfo that = (PackageInfo) o;

        if (jarPath != null ? !jarPath.equals(that.jarPath) : that.jarPath != null) return false;
        if (packageName != null ? !packageName.equals(that.packageName) : that.packageName != null) return false;
        if (licenseKey != null ? !licenseKey.equals(that.licenseKey) : that.licenseKey != null) return false;
        return version != null ? version.equals(that.version) : that.version == null;

    }

    @Override
    public int hashCode() {
        int result = jarPath != null ? jarPath.hashCode() : 0;
        result = 31 * result + (packageName != null ? packageName.hashCode() : 0);
        result = 31 * result + (licenseKey != null ? licenseKey.hashCode() : 0);
        result = 31 * result + (version != null ? version.hashCode() : 0);
        return result;
    }

    @Override
    public int compareTo(PackageInfo o) {
        int jarPathCompare = jarPath.compareTo(o.jarPath);
        if (jarPathCompare != 0) {
            return jarPathCompare;
        }
        int packageNameCompare = packageName.compareTo(o.packageName);
        if (packageNameCompare != 0) {
            return packageNameCompare;
        }
        int versionCompare = version.compareTo(o.version);
        return versionCompare;
    }
}
