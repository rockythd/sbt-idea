package org.sbtidea

/**
 * Copyright (C) 2010, Mikko Peltonen, Jon-Anders Teigen, Michal Příhoda, Graham Tackley, Ismael Juma, Odd Möller, Johannes Rudolph
 * Licensed under the new BSD License.
 * See the LICENSE file for details.
 */

import sbt._
import java.io.File

import xml.{Node, NodeSeq, Text}


class IdeaModuleDescriptor(val imlDir: File, projectRoot: File, val project: SubProjectInfo, val env: IdeaProjectEnvironment, val userEnv: IdeaUserEnvironment, val log: Logger) extends SaveableXml {
  val path = String.format("%s/%s.iml", imlDir.getAbsolutePath, project.name)

  def relativePath(file: File) = IOUtils.relativePath(projectRoot, file, "$MODULE_DIR$/../")

  val sources = project.compileDirs.sources.map(relativePath)
  val resources = project.compileDirs.resources.map(relativePath)
  val testSources = project.testDirs.sources.map(relativePath)
  val testResources = project.testDirs.resources.map(relativePath)

  def content: Node = {
    <module type="JAVA_MODULE" version="4">
      <component name="FacetManager">
        { if (project.includeScalaFacet) scalaFacet else scala.xml.Null }
        { (for (wap <- project.webAppPath if userEnv.webFacet) yield webFacet(relativePath(wap))).getOrElse(scala.xml.Null) }
        { project.androidSupport map (_.facet) getOrElse NodeSeq.Empty }
        { project.extraFacets }
      </component>
      <component name="NewModuleRootManager" inherit-compiler-output={env.projectOutputPath.isDefined.toString}>
        {
          if (env.projectOutputPath.isEmpty) {
            <output url={"file://" + relativePath(project.compileDirs.outDir)} />
            <output-test url={"file://" + relativePath(project.testDirs.outDir)} />
          } else scala.xml.Null
        }
        <exclude-output />
        <content url={"file://" + relativePath(project.baseDir) }>
          { sources.map(sourceFolder(_, false, project.packagePrefix)) }
          { resources.map(sourceFolder(_, false, project.packagePrefix)) }
          { testSources.map(sourceFolder(_, true, project.packagePrefix)) }
          { testResources.map(sourceFolder(_, true, project.packagePrefix)) }
          {

          val managed = project.compileDirs.sources ++ project.testDirs.sources

          def dontExcludeManagedSources(toExclude:File):Seq[File] = {

              def isParent(f:File):Boolean = {
                f == toExclude || (f != null && isParent(f.getParentFile))
              }
              val dontExclude = managed.exists(isParent)

              if(dontExclude)
                toExclude.listFiles().toSeq.filter(_.isDirectory).filterNot(managed.contains).flatMap(dontExcludeManagedSources)
              else
                Seq(toExclude)
            }

            env.excludedFolders
              .map(entry => new File(project.baseDir, entry.trim))
              .flatMap(dontExcludeManagedSources)
              .sortBy(_.getName).map { exclude =>
              log.info(String.format("Excluding folder %s\n", exclude))
              <excludeFolder url={String.format("file://%s", relativePath(exclude))} />
            }
          }
        </content>
        {
          /*project match {
            case sp: ScalaPaths if ! env.compileWithIdea.value =>
              val nodeBuffer = new xml.NodeBuffer
              if (sp.testResources.getFiles.exists(_.exists))
                nodeBuffer &+ moduleLibrary(Some("TEST"), None, None,
                  Some("file://$MODULE_DIR$/" + relativePath(sp.testResourcesOutputPath.asFile)), false)
              if (sp.mainResources.getFiles.exists(_.exists))
                nodeBuffer &+ moduleLibrary(None, None, None,
                  Some("file://$MODULE_DIR$/" + relativePath(sp.mainResourcesOutputPath.asFile)), false)
              nodeBuffer
            case _ => xml.Null
          }*/ xml.Null
        }
        {
          project.androidSupport map (_.moduleJdk) getOrElse <orderEntry type="inheritedJdk"/>
        }
        <orderEntry type="sourceFolder" forTests="false"/>
        {
        // what about j.extraAttributes.get("e:docUrl")?
        promoteTestEvictors(project.libraries).map(ref => {
          val scope = ref.config.configName.map(Text(_))
          <orderEntry type="library" name={ref.library.name} scope={scope} level="project"/>
        })
        }

        {
          project.dependencyProjects.map { dep =>
            log.debug("Project dependency: " + dep.name)
            val scope = dep.scope.configName.map(Text(_))
            <orderEntry type="module" module-name={dep.name} scope={scope} exported=""/>
          }
        }
        {
          project.classpathDeps.map { case (classesDir, sourceDirs) =>
            <orderEntry type="module-library">
              <library>
                <CLASSES>
                  <root url={ "file://%s".format(classesDir.getAbsolutePath) } />
                </CLASSES>
                <JAVADOC />
                <SOURCES>
                {
                  sourceDirs.filter(_.exists).map { srcDir =>
                    <root url={ "file://%s".format(srcDir.getAbsolutePath) } />
                  }
                }
                </SOURCES>
              </library>
            </orderEntry>
          }
        }
      </component>
    </module>
  }

  def sourceFolder(path: String, isTestSourceFolder: Boolean,
                   packagePrefix: Option[String]) = {
    val pkg = packagePrefix.map(Text(_))
    <sourceFolder url={"file://" + path}
                  isTestSource={isTestSourceFolder.toString}
                  packagePrefix={pkg} />
  }

  def scalaFacet: Node = {
    val pluginOptionName = "-Xplugin:"
    val nonXplugin = project.scalacOptions.filterNot(_.startsWith(pluginOptionName))
    val compilerOptions = Map(
      "-deprecation" -> <option name="deprecationWarnings" value="true" />,
      "-unchecked" -> <option name="uncheckedWarnings" value="true" />,
      "-P:continuations:enable" -> <option name="continuations" value="true" />,
      "-explaintypes" -> <option name="explainTypeErrors" value="true" />,
      "-optimise" -> <option name="optimiseBytecode" value="true" />,
      "-nowarn" -> <option name="warnings" value="false" />
    )
    <facet type="scala" name="Scala">
      <configuration>
        {
          project.basePackage.map(bp => <option name="basePackage" value={bp} />).getOrElse(scala.xml.Null)
        }
        <option name="compilerLibraryLevel" value="Project" />
        <option name="compilerLibraryName" value={ SbtIdeaModuleMapping.toIdeaLib(project.scalaInstance).name } />
        <option name="languageLevel" value={project.languageLevel}/>

        {
          if (env.useProjectFsc) <option name="fsc" value="true" />
        }
        {
          nonXplugin.filter(compilerOptions.contains).map(compilerOptions)
        }
        {
          val xplugin = project.scalacOptions.filter(_.startsWith(pluginOptionName)).map(_.substring(pluginOptionName.length))
          if (!xplugin.isEmpty){
            <option name="pluginPaths">
              <array>
                {
                  xplugin.map(x=> <option value={x} />)
                }
              </array>
            </option>
          }
        }
        {
        val options = nonXplugin.filterNot(compilerOptions.contains).mkString(" ")
        <option name="compilerOptions" value={options} />
        }
      </configuration>
    </facet>
  }

  def webFacet(relativeWebAppPath: String): Node =
    <facet type="web" name="Web">
      <configuration>
        <descriptors>
          <deploymentDescriptor name="web.xml" url={String.format("file://%s/WEB-INF/web.xml", relativeWebAppPath)}/>
        </descriptors>
        <webroots>
          <root url={String.format("file://%s", relativeWebAppPath)} relative="/"/>
        </webroots>
      </configuration>
    </facet>

  /**
   * SBT allows different versions of the same library in different scopes, evicting runtime/compile dependencies
   * out of their scopes if a newer one is found in test when compiling/running tests, while still maintaining
   * the order as defined in the build configuration. IDEA doesn't support this eviction.  We fake it by
   * detecting evictors, and moving them up in the classpath to just before the evictees.
   */
  def promoteTestEvictors(libraries: Seq[IdeaModuleLibRef]) = {
    val evictions = for {
      (evictionId, libs) <- libraries.groupBy(_.library.evictionId) if libs.size > 1
      testLib <- libs.find(_.config == IdeaLibrary.TestScope)
    } yield
      testLib -> libs.filterNot(_ == testLib)

    evictions.foldLeft(libraries){(libs, e) =>
      val (evictor, evictees) = e
      val evictorIndex = libs.indexOf(evictor)
      val firstEvicteeIndex = libs.indexWhere(l => evictees.contains(l))
      if (evictorIndex > firstEvicteeIndex) {
        (libs.take(firstEvicteeIndex) :+ evictor) ++ libs.takeRight(libs.size - firstEvicteeIndex).filterNot(_ == evictor)
      } else {
        libs
      }
    }
  }
}
