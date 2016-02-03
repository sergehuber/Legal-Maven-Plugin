package org.jahia.tools.maven.plugins;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.util.List;

/**
 * Aggregates NOTICE and LICENSE files from dependencies into aggregated versions.
 *
 * @goal aggregate-legal-artifacts
 * 
 * @phase process-resources
 */
public class AggregateLegalArtifactsMojo extends AbstractMojo
{

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    private List<RemoteRepository> projectRepos;

    /**
     * The project's remote repositories to use for the resolution of plugins and their dependencies.
     *
     * @parameter default-value="${project.remotePluginRepositories}"
     * @readonly
     */
    private List<RemoteRepository> pluginRepos;

    /**
     * SCM Manager component to be injected.
     * @component
     */
    private ScmManager scmManager;

    /**
     * The Maven project.
     *
     * @parameter property="project"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Location of the file.
     * @parameter property="project.build.directory"
     * @required
     */
    private File outputDirectory;

    /**
     * Whether or not to output diagnostics.
     *
     * @parameter default-value=false
     */
    private boolean outputDiagnostics;

    /**
     * Whether or not to be verbose when running i.e. output diagnostics plus additional information.
     *
     * @parameter default-value=false
     */
    private boolean verbose;

    public void execute()
        throws MojoExecutionException
    {
        File f = outputDirectory;

        if ( !f.exists() )
        {
            f.mkdirs();
        }

        LegalArtifactAggregator legalArtifactAggregator = new LegalArtifactAggregator(f, repoSystem, repoSession, projectRepos, verbose, outputDiagnostics);
        legalArtifactAggregator.execute();

        //get data from project
        if (project.getScm() != null) {
            String developerConnection = project.getScm().getDeveloperConnection();

            File wcDir = new File(project.getBuild().getDirectory(), "checkout");
            if (!wcDir.exists()) {
                wcDir.mkdirs();
            }
            ScmProvider scmProvider;
            try {
                scmProvider = scmManager.getProviderByUrl(developerConnection);
                ScmRepository scmRepository = scmManager.makeScmRepository(developerConnection);
                CheckOutScmResult scmResult = scmProvider.checkOut(scmRepository, new ScmFileSet(wcDir));
                if (!scmResult.isSuccess()) {
                    getLog().error(String.format("Fail to checkout artifact %s to %s", project.getArtifact(), wcDir));
                }
            } catch (Exception e) {
                throw new MojoExecutionException("Fail to checkout.", e);
            }
        }

    }
}
