/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2004,2005 Dave Brosius <dbrosius@users.sourceforge.net>
 * Copyright (C) 2004,2005 University of Maryland
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

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.StatelessDetector;
import edu.umd.cs.findbugs.ba.XClass;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.Global;

public class WrongMapIterator extends BytecodeScanningDetector implements StatelessDetector {
    private BugAccumulator bugAccumulator;

    private static final int SAW_NOTHING = 0;

    private static final int SAW_MAP_LOAD1 = 1;

    private static final int SAW_KEYSET = 2;

    private static final int SAW_KEYSET_STORE = 3;

    private static final int SAW_ITERATOR = 4;

    private static final int SAW_ITERATOR_STORE = 5;

    private static final int SAW_ITERATOR_LOAD = 6;

    private static final int SAW_NEXT = 7;

    private static final int SAW_CHECKCAST_ON_NEXT = 8;

    private static final int SAW_KEY_STORE = 9;

    private static final int NEED_KEYSET_LOAD = 10;

    private static final int SAW_MAP_LOAD2 = 11;

    private static final int SAW_KEY_LOAD = 12;

    private int state;

    private int loadedRegister;

    private int mapRegister;

    private int keySetRegister;

    private int iteratorRegister;

    private int keyRegister;

    public WrongMapIterator(BugReporter bugReporter) {
        this.bugAccumulator = new BugAccumulator(bugReporter);
    }

    @Override
    public void visit(Method obj) {
        state = SAW_NOTHING;
        loadedRegister = -1;
        mapRegister = -1;
        keySetRegister = -1;
        iteratorRegister = -1;
        keyRegister = -1;
    }

    @Override
    public void visit(Code code) {
        super.visit(code);
        bugAccumulator.reportAccumulatedBugs();
    }

    /**
     * Determine from the class descriptor for a variable whether that variable
     * implements java.util.Map.
     * 
     * @param d
     *            class descriptor for variable we want to check implements Map
     * @return true iff the descriptor corresponds to an implementor of Map
     */
    private static boolean implementsMap(ClassDescriptor d) {
        while (d != null) {
            try {
                // True if variable is itself declared as a Map
                if ("java.util.Map".equals(d.getDottedClassName())) {
                    return true;
                }
                XClass classNameAndInfo = Global.getAnalysisCache().getClassAnalysis(XClass.class, d);
                ClassDescriptor is[] = classNameAndInfo.getInterfaceDescriptorList();
                d = classNameAndInfo.getSuperclassDescriptor();
                for (ClassDescriptor i : is) {
                    if ("java.util.Map".equals(i.getDottedClassName())) {
                        return true;
                    }
                }
            } catch (CheckedAnalysisException e) {
                d = null;
            }
        }
        return false;
    }

    @Override
    public void sawOpcode(int seen) {
        switch (state) {
        case SAW_NOTHING:
            loadedRegister = getLoadStoreRegister(seen, true);
            if (loadedRegister >= 0)
                state = SAW_MAP_LOAD1;
            break;

        case SAW_MAP_LOAD1:
            // Doesn't check to see if the target object is a Map
            if (((seen == INVOKEINTERFACE) || (seen == INVOKEVIRTUAL)) && ("keySet".equals(getNameConstantOperand()))
                    && ("()Ljava/util/Set;".equals(getSigConstantOperand()))
                    // Following check solves sourceforge bug 1830576
                    && implementsMap(getClassDescriptorOperand())) {
                mapRegister = loadedRegister;
                state = SAW_KEYSET;
            } else {
                state = SAW_NOTHING;
            }
            break;

        case SAW_KEYSET:
            keySetRegister = getLoadStoreRegister(seen, false);
            if (keySetRegister >= 0)
                state = SAW_KEYSET_STORE;
            else if ((seen == INVOKEINTERFACE) && ("iterator".equals(getNameConstantOperand()))
                    && ("()Ljava/util/Iterator;".equals(getSigConstantOperand())))
                state = SAW_ITERATOR;
            else
                state = SAW_NOTHING;
            break;

        case SAW_KEYSET_STORE:
            if ((seen == INVOKEINTERFACE) && ("iterator".equals(getNameConstantOperand()))
                    && ("()Ljava/util/Iterator;".equals(getSigConstantOperand())))
                state = SAW_ITERATOR;
            else
                state = NEED_KEYSET_LOAD;
            break;

        case NEED_KEYSET_LOAD:
            loadedRegister = getLoadStoreRegister(seen, true);
            if (loadedRegister == iteratorRegister)
                state = SAW_ITERATOR;
            break;

        case SAW_ITERATOR:
            iteratorRegister = getLoadStoreRegister(seen, false);
            if (iteratorRegister >= 0)
                state = SAW_ITERATOR_STORE;
            else
                state = SAW_NOTHING;
            break;

        case SAW_ITERATOR_STORE:
            loadedRegister = getLoadStoreRegister(seen, true);
            if (loadedRegister == iteratorRegister)
                state = SAW_ITERATOR_LOAD;
            break;

        case SAW_ITERATOR_LOAD:
            if ((seen == INVOKEINTERFACE) && ("next".equals(getNameConstantOperand()))
                    && ("()Ljava/lang/Object;".equals(getSigConstantOperand())))
                state = SAW_NEXT;
            else
                state = SAW_ITERATOR_STORE;
            break;

        case SAW_NEXT:
            if (seen == CHECKCAST)
                state = SAW_CHECKCAST_ON_NEXT;
            else {
                keyRegister = getLoadStoreRegister(seen, false);
                if (keyRegister >= 0)
                    state = SAW_KEY_STORE;
                else
                    state = SAW_NOTHING;
            }
            break;

        case SAW_CHECKCAST_ON_NEXT:
            keyRegister = getLoadStoreRegister(seen, false);
            if (keyRegister >= 0)
                state = SAW_KEY_STORE;
            break;

        case SAW_KEY_STORE:
            loadedRegister = getLoadStoreRegister(seen, true);
            if (loadedRegister == mapRegister)
                state = SAW_MAP_LOAD2;
            break;

        case SAW_MAP_LOAD2:
            loadedRegister = getLoadStoreRegister(seen, true);
            if (loadedRegister == keyRegister)
                state = SAW_KEY_LOAD;
            else
                state = SAW_KEY_STORE;
            break;

        case SAW_KEY_LOAD:
            if (((seen == INVOKEINTERFACE) || (seen == INVOKEVIRTUAL)) && ("get".equals(getNameConstantOperand()))
                    && ("(Ljava/lang/Object;)Ljava/lang/Object;".equals(getSigConstantOperand()))) {
                MethodAnnotation ma = MethodAnnotation.fromVisitedMethod(this);
                bugAccumulator.accumulateBug(new BugInstance(this, "WMI_WRONG_MAP_ITERATOR", NORMAL_PRIORITY).addClass(this)
                        .addMethod(ma), this);
                state = SAW_NOTHING;
            }
            break;
        }
    }

    private int getLoadStoreRegister(int seen, boolean doLoad) {
        switch (seen) {
        case ALOAD_0:
        case ALOAD_1:
        case ALOAD_2:
        case ALOAD_3:
            if (doLoad)
                return seen - ALOAD_0;
            break;

        case ALOAD:
            if (doLoad)
                return getRegisterOperand();
            break;

        case ASTORE_0:
        case ASTORE_1:
        case ASTORE_2:
        case ASTORE_3:
            if (!doLoad)
                return seen - ASTORE_0;
            break;

        case ASTORE:
            if (!doLoad)
                return getRegisterOperand();
            break;
        }

        return -1;
    }
}
