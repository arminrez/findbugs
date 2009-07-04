/*
 * FindBugs - Find Bugs in Java programs
 * Copyright (C) 2003-2008 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.Detector2;
import edu.umd.cs.findbugs.DetectorFactory;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.NonReportingDetector;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.annotations.ExpectWarning;
import edu.umd.cs.findbugs.annotations.NoWarning;
import edu.umd.cs.findbugs.ba.XClass;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.DescriptorFactory;
import edu.umd.cs.findbugs.classfile.Global;
import edu.umd.cs.findbugs.classfile.MethodDescriptor;
import edu.umd.cs.findbugs.classfile.analysis.AnnotationValue;
import edu.umd.cs.findbugs.plan.AnalysisPass;
import edu.umd.cs.findbugs.plan.ExecutionPlan;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Check uses of the ExpectWarning and NoWarning annotations.
 * This is for internal testing of FindBugs (against findbugsTestCases).
 * 
 * @author David Hovemeyer
 */
public class CheckExpectedWarnings implements Detector2, NonReportingDetector {
	private static final boolean DEBUG = SystemProperties.getBoolean("cew.debug");
	
	private BugCollectionBugReporter reporter;
	private Set<String> possibleBugCodes;
	private Map<MethodDescriptor, Collection<BugInstance>> warningsByMethod;
	
	private ClassDescriptor expectWarning;
	private ClassDescriptor noWarning;
	private boolean warned;
	
	public CheckExpectedWarnings(BugReporter bugReporter) {
		BugReporter realBugReporter = bugReporter.getRealBugReporter();
		if (realBugReporter instanceof BugCollectionBugReporter) {
			reporter = (BugCollectionBugReporter) realBugReporter;
			expectWarning = DescriptorFactory.createClassDescriptor(ExpectWarning.class);
			noWarning = DescriptorFactory.createClassDescriptor(NoWarning.class);
		}
	}

	public void visitClass(ClassDescriptor classDescriptor) throws CheckedAnalysisException {
		if (reporter == null) {
			if (!warned) {
				System.err.println("*** NOTE ***: CheckExpectedWarnings disabled because bug reporter doesn't use a BugCollection");
				warned = true;
			}
			return;
		}

		if (warningsByMethod == null) {
			//
			// Build index of all warnings reported so far, by method.
			// Because this detector runs in a later pass than any
			// reporting detector, all warnings should have been
			// produced by this point.
			//
			
			warningsByMethod = new HashMap<MethodDescriptor, Collection<BugInstance>>();
			BugCollection bugCollection = reporter.getBugCollection();
			
			for (Iterator<BugInstance> i = bugCollection.iterator(); i.hasNext(); ){
				BugInstance warning = i.next();
				MethodAnnotation method = warning.getPrimaryMethod();
				if (method != null) {
					MethodDescriptor methodDesc = method.toXMethod().getMethodDescriptor();
					Collection<BugInstance> warnings = warningsByMethod.get(methodDesc);
					if (warnings == null) {
						warnings = new LinkedList<BugInstance>();
						warningsByMethod.put(methodDesc, warnings);
					}
					warnings.add(warning);
				}
			}
			
			//
			// Based on enabled detectors, figure out which bug codes
			// could possibly be reported.  Don't complain about
			// expected warnings that would be produced by detectors
			// that aren't enabled.
			//
			
			possibleBugCodes = new HashSet<String>();
			ExecutionPlan executionPlan = Global.getAnalysisCache().getDatabase(ExecutionPlan.class);
			Iterator<AnalysisPass> i = executionPlan.passIterator();
			while (i.hasNext()) {
				AnalysisPass pass = i.next();
				Iterator<DetectorFactory> j = pass.iterator();
				while (j.hasNext()) {
					DetectorFactory factory = j.next();
					
					Collection<BugPattern> reportedPatterns = factory.getReportedBugPatterns();
					for (BugPattern pattern : reportedPatterns) {
						possibleBugCodes.add(pattern.getAbbrev());
					}
				}
			}
			if (DEBUG) {
				System.out.println("CEW: possible warnings are " + possibleBugCodes);
			}
		}
		
		XClass xclass = Global.getAnalysisCache().getClassAnalysis(XClass.class, classDescriptor);
		List<? extends XMethod> methods = xclass.getXMethods();
		for (XMethod xmethod : methods) {
			if (DEBUG) {
				System.out.println("CEW: checking " + xmethod.toString());
			}
			check(xmethod, expectWarning, true);
			check(xmethod, noWarning, false);
		}

	}

	private void check(XMethod xmethod, ClassDescriptor annotation, boolean expectWarnings) {
		AnnotationValue expect = xmethod.getAnnotation(annotation);
		if (expect != null) {
			if (DEBUG) {
				System.out.println("*** Found " + annotation + " annotation");
			}
			String expectedBugCodes = (String) expect.getValue("value");
			StringTokenizer tok = new StringTokenizer(expectedBugCodes, ",");
			while (tok.hasMoreTokens()) {
				String bugCode = tok.nextToken();
				int count = countWarnings(xmethod.getMethodDescriptor(), bugCode);
				if (DEBUG) {
					System.out.println("  *** Found " + count + " " + bugCode + " warnings");
				}
				if (expectWarnings && count == 0 && possibleBugCodes.contains(bugCode)) {
					reporter.reportBug(new BugInstance(this, "FB_MISSING_EXPECTED_WARNING", NORMAL_PRIORITY).addClassAndMethod(xmethod.getMethodDescriptor()).
							addString(bugCode));
				} else if (!expectWarnings && count > 0) {
					reporter.reportBug(new BugInstance(this, "FB_UNEXPECTED_WARNING", NORMAL_PRIORITY).addClassAndMethod(xmethod.getMethodDescriptor()).
							addString(bugCode));
				}
			}
		}
	}

	private int countWarnings(MethodDescriptor methodDescriptor, String bugCode) {
		int count = 0;
		Collection<BugInstance> warnings = warningsByMethod.get(methodDescriptor);
		if (warnings != null) {
			for (BugInstance warning : warnings) {
				BugPattern pattern = warning.getBugPattern();
				if (pattern.getAbbrev().equals(bugCode)) {
					count++;
				}
			}
		}
		return count;
	}
	
	public void finishPass() {
		// Nothing to do
	}

	public String getDetectorClassName() {
		return CheckExpectedWarnings.class.getName();
	}

}
