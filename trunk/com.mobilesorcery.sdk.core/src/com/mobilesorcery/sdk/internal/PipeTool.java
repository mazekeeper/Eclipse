/*  Copyright (C) 2009 Mobile Sorcery AB

    This program is free software; you can redistribute it and/or modify it
    under the terms of the Eclipse Public License v1.0.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE. See the Eclipse Public License v1.0 for
    more details.

    You should have received a copy of the Eclipse Public License v1.0 along
    with this program. It is also available at http://www.eclipse.org/legal/epl-v10.html
*/
package com.mobilesorcery.sdk.internal;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;

import com.mobilesorcery.sdk.core.CoreMoSyncPlugin;
import com.mobilesorcery.sdk.core.IProcessConsole;
import com.mobilesorcery.sdk.core.MoSyncBuilder;
import com.mobilesorcery.sdk.core.MoSyncTool;
import com.mobilesorcery.sdk.core.Util;
import com.mobilesorcery.sdk.core.LineReader.ILineHandler;

public class PipeTool {

    public static final String BUILD_RESOURCES_MODE = "-R";
    public static final String BUILD_C_MODE = "-B";
    public static final String BUILD_LIB_MODE = "-L";
	private static final String RESOURCE_DEPENDENCY_FILE_NAME = "resources.deps";
	private static final int MAX_PIPE_TOOL_ARG_LENGTH = 162;

    private IPath outputFile;
    private String[] inputFiles;
    private IProcessConsole console;
    private IPath[] libraryPaths;
    private String mode;
    private boolean sld = true;
    private IPath[] libraries;
    private boolean dce;
    private IPath exeDir;
    private boolean noVerify;
    private String[] extra;
    private IProject project;
    private ILineHandler linehandler;
	private boolean collectStabs;

    public PipeTool() {

    }

    public void setLibraryPaths(IPath[] libraryPaths) {
        this.libraryPaths = libraryPaths;
    }

    public void setLibraries(IPath[] libraries) {
        this.libraries = libraries;
    }

    public void setConsole(IProcessConsole console) {
        this.console = console;
    }

    public void setOutputFile(IPath outputPath) {
        this.outputFile = outputPath;
    }

    public void setInputFiles(String[] objectFiles) {
        this.inputFiles = objectFiles;
    }

    public void setGenerateSLD(boolean sld) {
        this.sld = sld;
    }

    public void run() throws CoreException {
        IPath pipeTool = MoSyncTool.getDefault().getMoSyncBin().append("pipe-tool.exe");

        ArrayList<String> args = new ArrayList<String>();

        args.add(pipeTool.toOSString());

        if (extra != null && Util.join(extra, "").trim().length() > 0) {
            args.addAll(Arrays.asList(extra));
        }

        if (collectStabs) {
        	args.add("-collect-stabs");
        }
        
        if (BUILD_C_MODE == mode || BUILD_LIB_MODE == mode) {
            if (sld) {
                args.add("-sld");
            }

            if (libraryPaths != null) {
                args.addAll(Arrays.asList(assembleLibraryPathArgs(libraryPaths)));
            }
        }

        if (dce) {
            args.add("-elim");
        }

        if (noVerify) {
            args.add("-no-verify");
        }

        args.add(mode);

        if (BUILD_RESOURCES_MODE == mode) {
        	IPath depsFile = getResourcesDependencyFile(project);
        	depsFile.toFile().getParentFile().mkdirs();
        	// Pipetool only accepts -depend files in exeuction dir        
        	args.add(Util.ensureQuoted("-depend=" + depsFile.toOSString()));
        }
        
        args.add(Util.ensureQuoted(outputFile.toOSString()));

        args.addAll(Arrays.asList(Util.ensureQuoted(inputFiles)));

        if (BUILD_C_MODE == mode || BUILD_LIB_MODE == mode) {
            for (int i = 0; libraries != null && i < libraries.length; i++) {
                args.add(libraries[i].toOSString());
            }
        }

        addMessage(Util.join(args.toArray(new String[0]), " "));

        try {
            assertArgLength(args);
            
            outputFile.toFile().getParentFile().mkdirs();
            
            // Note where it's executed - this is where the sld will end up.
            Process process = DebugPlugin.exec((String[]) args.toArray(new String[0]), getExecDir());

            String cmdLine = Util.join(Util.ensureQuoted(args.toArray()), " ");
            
            if (CoreMoSyncPlugin.getDefault().isDebugging()) {
            	CoreMoSyncPlugin.trace(cmdLine);            	
            }
            
            console.attachProcess(process, linehandler);
            int result = process.waitFor();
            if (result != 0) {
                throw new CoreException(new Status(IStatus.ERROR, CoreMoSyncPlugin.PLUGIN_ID,
                        MessageFormat.format("Pipe tool failed. (See console for more information).\n Command line: {0}", cmdLine)));                
            }
        } catch (CoreException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CoreException(new Status(IStatus.ERROR, CoreMoSyncPlugin.PLUGIN_ID, e.getMessage(), e));
        }
    }

    private void assertArgLength(ArrayList<String> args) throws IOException {
    	for (String arg : args) {
    		if (arg.length() > MAX_PIPE_TOOL_ARG_LENGTH) {
    			throw new IOException(MessageFormat.format("Argument/file name too long: {0} (max length {1} characters)", arg, MAX_PIPE_TOOL_ARG_LENGTH));
    		}
    	}
	}

	public static IPath getResourcesDependencyFile(IProject project) {
    	return MoSyncBuilder.getOutputPath(project).append(RESOURCE_DEPENDENCY_FILE_NAME);
    }

	private String[] assembleLibraryPathArgs(IPath[] libraryPaths) {
        String[] result = new String[libraryPaths.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = Util.ensureQuoted("-s" + libraryPaths[i].toOSString());
        }

        return result;
    }

    private void addMessage(String message) {
        if (console != null) {
            console.addMessage(message);
        }
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setDeadCodeElimination(boolean dce) {
        this.dce = dce;
    }

    public File getExecDir() {
    	return getExecDir(project);
        // return outputFile.toFile().getParentFile().getParentFile();
    }

    private static File getExecDir(IProject project) {
    	return project.getLocation().toFile();
    }
    
    public void setNoVerify(boolean noVerify) {
        this.noVerify = noVerify;
    }

    public void setExtraSwitches(String[] extra) {
        this.extra = extra;
    }

    public void setProject(IProject project) {
        this.project = project;
    }

    public void setLineHandler(ILineHandler linehandler) {
        this.linehandler = linehandler;
    }

	public void setCollectStabs(boolean collectStabs) {
		this.collectStabs = collectStabs;
	}
}