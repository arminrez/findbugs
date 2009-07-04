/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2006 University of Maryland
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

import java.util.HashSet;
import java.util.Set;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.ClassAnnotation;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.XClass;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.util.ClassName;
import edu.umd.cs.findbugs.util.EditDistance;

public class UncallableMethodOfAnonymousClass extends BytecodeScanningDetector {

	BugReporter bugReporter;

	public UncallableMethodOfAnonymousClass(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
	}

	

	XMethod potentialSuperCall;
	@Override
	public void visitJavaClass(JavaClass obj) {
		try {
	       obj.getSuperClass();
        } catch (ClassNotFoundException e) {
	       AnalysisContext.reportMissingClass(e);
	       return;
        }
		
		String superclassName2 = getSuperclassName();
		boolean weird = superclassName2.equals("java.lang.Object") && obj.getInterfaceIndices().length == 0;
		boolean hasAnonymousName = ClassName.isAnonymous(obj.getClassName());
		boolean isAnonymousInnerClass = hasAnonymousName && !weird;
		if (isAnonymousInnerClass)
			super.visitJavaClass(obj);
	}

	boolean definedInThisClassOrSuper(JavaClass clazz, String method)
			throws ClassNotFoundException {
		if (clazz == null)
			return false;
		// System.out.println("Checking to see if " + method + " is defined in "
		// + clazz.getClassName());
		for (Method m : clazz.getMethods())
			if (!m.isStatic() && method.equals(m.getName() + ":" + m.getSignature()))
				return true;

		return definedInSuperClassOrInterface(clazz, method);

	}

	@Override
    public void sawOpcode(int seen) {
		if (seen == INVOKESPECIAL) {
			XMethod m = getXMethodOperand();
			if (m == null) 
				return;
			XClass c = getXClass();
			int nameDistance = EditDistance.editDistance(m.getName(), getMethodName());
			if (nameDistance < 4 && c.findMatchingMethod(m.getMethodDescriptor())
					== null && !m.isFinal())
				potentialSuperCall = m;
		}
	}
	boolean definedInSuperClassOrInterface(JavaClass clazz, String method)
			throws ClassNotFoundException {
		if (clazz == null)
			return false;
		JavaClass superClass = clazz.getSuperClass();
		if (definedInThisClassOrSuper(superClass, method))
			return true;
		for (JavaClass i : clazz.getInterfaces())
			if (definedInThisClassOrSuper(i, method))
				return true;
		return false;
	}

	Set<String> definedInClass(JavaClass clazz) {
		HashSet<String> result = new HashSet<String>();
		for(Method m : clazz.getMethods()) {
			if (!skip(m)) 
				result.add(m.getName()+m.getSignature());
		}
		return result;
	}

	 private boolean skip(Method obj) {
		if (obj.isSynthetic())
			return true;
		if (obj.isPrivate())
			return true;
		if (obj.isAbstract()) 
			return true;

		String methodName = obj.getName();
		String sig = obj.getSignature();
		if (methodName.equals("<init>"))
			return true;
		if (methodName.equals("<clinit>"))
			return true;
		if (sig.equals("()Ljava/lang/Object;") 
				&& (methodName.equals("readResolve") 
						|| methodName.equals("writeReplace")))
			return true;
		if (methodName.startsWith("access$"))
			return true;
		if (methodName.length() < 2 || methodName.indexOf('$') >= 0)
			return true;
		XMethod m = getXMethod();
		for(ClassDescriptor c : m.getAnnotationDescriptors()) 
			if (c.getClassName().indexOf("inject") >= 0)
				return true;
		return false;
	}
	 
	 
	 BugInstance pendingBug;
		@Override
		public void doVisitMethod(Method obj) {
			super.doVisitMethod(obj);
			if (pendingBug != null) {
				if (potentialSuperCall == null) {
					String role = ClassAnnotation.SUPERCLASS_ROLE;

					String superclassName = ClassName.toDottedClassName(getSuperclassName());
					if (superclassName.equals("java.lang.Object")) {
						
                        try {
                        	JavaClass interfaces[] = getThisClass().getInterfaces();
	                        if (interfaces.length == 1) {
								superclassName = interfaces[0].getClassName();
								role = ClassAnnotation.IMPLEMENTED_INTERFACE_ROLE;
							}
                        } catch (ClassNotFoundException e) {
	                        AnalysisContext.reportMissingClass(e);
                        }
					}
					pendingBug.addClass(superclassName).describe(role);
                } else  {
					pendingBug.setPriority(pendingBug.getPriority()-1);
					pendingBug.addMethod(potentialSuperCall).describe(MethodAnnotation.METHOD_DID_YOU_MEAN_TO_OVERRIDE);
				}
				bugReporter.reportBug(pendingBug);
				pendingBug = null;
				potentialSuperCall = null;
			}

			
			
		}
		@Override
		public void visit(Code obj) {
			if (pendingBug != null)
				super.visit(obj);
		}

	@Override
	public void visit(Method obj) {
		try {

			if (skip(obj)) return;

			JavaClass clazz = getThisClass();
			XMethod xmethod = XFactory.createXMethod(clazz, obj);
			XFactory factory = AnalysisContext.currentXFactory();
			if (!factory.isCalled(xmethod)
					&& (obj.isStatic() || 
							!definedInSuperClassOrInterface(clazz, obj.getName() + ":" + obj.getSignature()))) {
				int priority = NORMAL_PRIORITY;
				JavaClass superClass = clazz.getSuperClass();
				String superClassName = superClass.getClassName();
				if (superClassName.equals("java.lang.Object")) {
						priority = NORMAL_PRIORITY;
						
				}
				else if (definedInClass(superClass).containsAll(definedInClass(clazz)))
						priority = NORMAL_PRIORITY;
				else
					priority = HIGH_PRIORITY;
				Code code = null;
				for(Attribute a : obj.getAttributes()) 
					if (a instanceof Code) {
						code = (Code) a;
						break;
					}
				if (code != null && code.getLength() == 1) 
					priority++; // TODO: why didn't FindBugs give a warning here before the null check was added?
				
				pendingBug = new BugInstance(this, "UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS",
						priority).addClassAndMethod(this);
				potentialSuperCall = null;
			}

		} catch (ClassNotFoundException e) {
			bugReporter.reportMissingClass(e);
		}

	}

}
