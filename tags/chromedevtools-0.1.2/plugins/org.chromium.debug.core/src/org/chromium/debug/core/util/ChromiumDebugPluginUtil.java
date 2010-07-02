// Copyright (c) 2009 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.debug.core.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;

import org.chromium.debug.core.ChromiumDebugPlugin;
import org.chromium.debug.core.efs.ChromiumScriptFileSystem;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 * A utility for interaction with the Eclipse workspace.
 */
public class ChromiumDebugPluginUtil {

  public static final String CHROMIUM_EXTENSION = "chromium"; //$NON-NLS-1$

  public static final String JS_DEBUG_PROJECT_NATURE = "org.chromium.debug.core.jsnature"; //$NON-NLS-1$

  public static final String CHROMIUM_EXTENSION_SUFFIX = "." + CHROMIUM_EXTENSION; //$NON-NLS-1$

  private static final String PROJECT_EXPLORER_ID = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$

  /**
   * Brings up the "Project Explorer" view in the active workbench window.
   */
  public static void openProjectExplorerView() {
    Display.getDefault().asyncExec(new Runnable() {
      public void run() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if (window == null) {
          if (workbench.getWorkbenchWindowCount() == 1) {
            window = workbench.getWorkbenchWindows()[0];
          }
        }
        if (window != null) {
          try {
            window.getActivePage().showView(PROJECT_EXPLORER_ID);
          } catch (PartInitException e) {
            // ignore
          }
        }
      }
    });
  }

  /**
   * Creates an empty workspace project with the name starting with the given projectNameBase.
   * Created project is guaranteed to be new in EFS, but workspace may happen to
   * alreay have project with such url (left uncleaned from previous runs). Such project
   * silently gets deleted.
   * @param projectNameBase project name template
   * @return the newly created project, or {@code null} if the creation failed
   */
  public static IProject createEmptyProject(String projectNameBase) {
    URI projectUri;
    String projectName;
    try {
      for (int uniqueNumber = 0; ; uniqueNumber++) {
        String projectNameTry;
        if (uniqueNumber == 0) {
          projectNameTry = projectNameBase;
        } else {
          projectNameTry = projectNameBase + " (" + uniqueNumber + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        URI projectUriTry = ChromiumScriptFileSystem.getFileStoreUri(
            new Path(null, "/" + projectNameTry)); //$NON-NLS-1$
        IFileStore projectStore = EFS.getStore(projectUriTry);
        if (projectStore.fetchInfo().exists()) {
          continue;
        } else {
          projectUri = projectUriTry;
          projectName = projectNameTry;
          break;
        }
      }
    } catch (CoreException e) {
      ChromiumDebugPlugin.log(e);
      return null;
    }
    IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
    IProjectDescription description =
        ResourcesPlugin.getWorkspace().newProjectDescription(projectName);
    description.setLocationURI(projectUri);
    description.setNatureIds(new String[] { JS_DEBUG_PROJECT_NATURE });
    try {
      if (project.exists()) {
        project.delete(true, null);
      }

      project.create(description, null);
      project.open(null);

      return project;
    } catch (CoreException e) {
      ChromiumDebugPlugin.log(e);
    }

    return null;
  }


  /**
   * Removes virtual project which was created for debug session. Does its job
   * asynchronously.
   */
  public static void deleteVirtualProjectAsync(final IProject debugProject) {
    Job job = new Job("Remove virtual project") {
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        URI projectUri = debugProject.getLocationURI();
        try {
          IFileStore projectStore = EFS.getStore(projectUri);
          if (projectStore.fetchInfo().exists()) {
            projectStore.delete(EFS.NONE, null);
          }
          debugProject.delete(true, null);
        } catch (CoreException e) {
          ChromiumDebugPlugin.log(e);
          return new Status(IStatus.ERROR, ChromiumDebugPlugin.PLUGIN_ID,
              "Failed to delete virtual project");
        }
        return Status.OK_STATUS;
      }
    };

    job.schedule();
  }

  /**
   * @param projectName to check for existence
   * @return whether the project named projectName exists.
   */
  public static boolean projectExists(String projectName) {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    IProject proj = ws.getRoot().getProject(projectName);
    return proj.exists();
  }

  /**
   * Creates an empty file with the given filename in the given project.
   *
   * @param project to create the file in
   * @param filename the base file name to create (will be sanitized for
   *        illegal chars and, in the case of a name clash, suffixed with "(N)")
   * @return the result of IFile.getName(), or {@code null} if the creation
   *         has failed
   */
  public static IFile createFile(IProject project, String filename) {
    String patchedName = new File(filename).getName().replace('?', '_'); // simple name
    String uniqueName = patchedName;

    // TODO(apavlov): refactor this?
    for (int i = 1; i < 1000; ++i) {
      String filePathname = uniqueName + CHROMIUM_EXTENSION_SUFFIX;
      IFile file = project.getFile(filePathname);

      if (file.exists()) {
        uniqueName = new StringBuilder(patchedName)
            .append(" (") //$NON-NLS-1$
            .append(i)
            .append(')')
            .toString();
      } else {
        try {
          file.create(new ByteArrayInputStream("".getBytes()), false, null); //$NON-NLS-1$
        } catch (CoreException e) {
          ChromiumDebugPlugin.log(e);
          return null;
        }
        return file;
      }
    }

    // Can we have 1000 same-named files?
    return null;
  }

  /**
   * Writes data into a resource with the given resourceName residing in the
   * source folder of the given project. The previous file content is lost.
   * Temporarily resets the "read-only" file attribute if one is present.
   *
   * @param file to set contents for
   * @param data to write into the file
   * @throws CoreException
   */
  public static void writeFile(IFile file, String data) throws CoreException {
    if (file != null && file.exists()) {
      ResourceAttributes resourceAttributes = file.getResourceAttributes();
      if (resourceAttributes.isReadOnly()) {
        resourceAttributes.setReadOnly(false);
        file.setResourceAttributes(resourceAttributes);
      }
      file.setContents(new ByteArrayInputStream(data.getBytes()), IFile.FORCE, null);
      resourceAttributes.setReadOnly(true);
      file.setResourceAttributes(resourceAttributes);
    }
  }

  public static boolean isInteger(String value) {
    try {
      Integer.parseInt(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * The container where the script sources should be put.
   *
   * @param project where the launch configuration stores the scripts
   * @return the script source container
   */
  public static IContainer getSourceContainer(IProject project) {
    return project;
  }

  private ChromiumDebugPluginUtil() {
    // not instantiable
  }
}