/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 University of Maryland
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

import java.math.BigDecimal;
import java.util.Iterator;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.CodeException;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantDouble;
import org.apache.bcel.classfile.ConstantInteger;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.ConstantValue;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.Synthetic;
import org.apache.bcel.generic.ObjectType;
import org.apache.bcel.generic.ReferenceType;
import org.apache.bcel.generic.Type;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.ClassAnnotation;
import edu.umd.cs.findbugs.IntAnnotation;
import edu.umd.cs.findbugs.JavaVersion;
import edu.umd.cs.findbugs.LocalVariableAnnotation;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.Item;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.StringAnnotation;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.Hierarchy;
import edu.umd.cs.findbugs.ba.ObjectTypeFactory;
import edu.umd.cs.findbugs.ba.SignatureParser;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.ch.Subtypes2;
import edu.umd.cs.findbugs.ba.type.TypeDataflow;
import edu.umd.cs.findbugs.bcel.OpcodeStackDetector;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.DescriptorFactory;
import edu.umd.cs.findbugs.classfile.MethodDescriptor;
import edu.umd.cs.findbugs.util.ClassName;
import edu.umd.cs.findbugs.util.Util;
import edu.umd.cs.findbugs.visitclass.PreorderVisitor;

public class DumbMethods extends OpcodeStackDetector {

    private static final ObjectType CONDITION_TYPE = ObjectTypeFactory.getInstance("java.util.concurrent.locks.Condition");

    private final BugReporter bugReporter;

    private boolean sawCurrentTimeMillis;

    private BugInstance gcInvocationBugReport;

    private int gcInvocationPC;

    private CodeException[] exceptionTable;

    /*
     * private boolean sawLDCEmptyString;
     */
    private String primitiveObjCtorSeen;

    private boolean ctorSeen;

    private boolean prevOpcodeWasReadLine;

    private int prevOpcode;

    private boolean isPublicStaticVoidMain;

    private boolean isEqualsObject;

    private boolean sawInstanceofCheck;

    private boolean reportedBadCastInEquals;

    private int sawCheckForNonNegativeSignedByte;

    private int sinceBufferedInputStreamReady;

    private int randomNextIntState;

    private boolean checkForBitIorofSignedByte;

    private final boolean jdk15ChecksEnabled;

    private final BugAccumulator accumulator;
    private final BugAccumulator absoluteValueAccumulator;

    private static final int MICROS_PER_DAY_OVERFLOWED_AS_INT
            = 24 * 60 * 60 * 1000 * 1000;

    public DumbMethods(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
        accumulator = new BugAccumulator(bugReporter);
        absoluteValueAccumulator = new BugAccumulator(bugReporter);
        jdk15ChecksEnabled = JavaVersion.getRuntimeVersion().isSameOrNewerThan(JavaVersion.JAVA_1_5);
    }

    boolean isSynthetic;

    @Override
    public void visit(JavaClass obj) {
        String superclassName = obj.getSuperclassName();
        isSynthetic = superclassName.equals("java.rmi.server.RemoteStub");
        Attribute[] attributes = obj.getAttributes();
        if (attributes != null) {
            for (Attribute a : attributes) {
                if (a instanceof Synthetic) {
                    isSynthetic = true;
                }
            }
        }

    }

    @Override
    public void visitAfter(JavaClass obj) {
        accumulator.reportAccumulatedBugs();
    }

    public static boolean isTestMethod(Method method) {
        return method.getName().startsWith("test");
    }

    @Override
    public void visit(Field field) {
        ConstantValue value = field.getConstantValue();
        if (value == null) return;
        Constant c = getConstantPool().getConstant(value.getConstantValueIndex());

        if (c instanceof ConstantLong && ((ConstantLong)c).getBytes()  == MICROS_PER_DAY_OVERFLOWED_AS_INT) {
            bugReporter.reportBug( new BugInstance(this, "TESTING", HIGH_PRIORITY).addClass(this).addField(this)
            .addString("Did you mean MICROS_PER_DAY")
            .addInt(MICROS_PER_DAY_OVERFLOWED_AS_INT)
            .describe(IntAnnotation.INT_VALUE));

        }
    }
    @Override
    public void visit(Method method) {
        String cName = getDottedClassName();

        // System.out.println(getFullyQualifiedMethodName());
        isPublicStaticVoidMain = method.isPublic() && method.isStatic() && getMethodName().equals("main")
                || cName.toLowerCase().indexOf("benchmark") >= 0;
        prevOpcodeWasReadLine = false;
        Code code = method.getCode();
        if (code != null) {
            this.exceptionTable = code.getExceptionTable();
        }
        if (this.exceptionTable == null) {
            this.exceptionTable = new CodeException[0];
        }
        primitiveObjCtorSeen = null;
        ctorSeen = false;
        randomNextIntState = 0;
        checkForBitIorofSignedByte = false;
        isEqualsObject = getMethodName().equals("equals") && getMethodSig().equals("(Ljava/lang/Object;)Z") && !method.isStatic();
        sawInstanceofCheck = false;
        reportedBadCastInEquals = false;
        freshRandomOnTos = false;
        sinceBufferedInputStreamReady = 100000;
        sawCheckForNonNegativeSignedByte = -1000;
        sawLoadOfMinValue = false;
        previousMethodCall = null;

    }

    int opcodesSincePendingAbsoluteValueBug;

    BugInstance pendingAbsoluteValueBug;

    SourceLineAnnotation pendingAbsoluteValueBugSourceLine;

    boolean freshRandomOnTos = false;

    boolean freshRandomOneBelowTos = false;
    
    boolean sawLoadOfMinValue = false;

    MethodDescriptor previousMethodCall = null;

    @Override
    public void sawOpcode(int seen) {
        
        if (isMethodCall()) {
            MethodDescriptor called = getMethodDescriptorOperand();

            if (previousMethodCall != null && !stack.isJumpTarget(getPC())) {
                if (called.getName().equals("toString")
                        && called.getClassDescriptor().getClassName().equals("java/lang/Integer")
                        && previousMethodCall.getName().equals("valueOf")
                        && previousMethodCall.getSignature().equals("(I)Ljava/lang/Integer;")
                        ) {
                    MethodAnnotation preferred = new MethodAnnotation("java.lang.Integer", "toString", "(I)Ljava/lang/String;", true);
                    BugInstance bug = new BugInstance(this, "DM_BOXED_PRIMITIVE_TOSTRING", HIGH_PRIORITY).addClassAndMethod(this)
                            .addCalledMethod(this).addMethod(preferred).describe(MethodAnnotation.SHOULD_CALL);
                    accumulator.accumulateBug(bug, this);

                }  else if (called.getName().equals("intValue")
                        && called.getClassDescriptor().getClassName().equals("java/lang/Integer")
                        && previousMethodCall.getSlashedClassName().equals("java/lang/Integer")
                        && (previousMethodCall.getName().equals("<init>")
                                && previousMethodCall.getSignature().equals("(Ljava/lang/String;)V")
                                || previousMethodCall.getName().equals("valueOf")
                                && previousMethodCall.getSignature().equals("(Ljava/lang/String;)Ljava/lang/Integer;")
                                )) {

                    MethodAnnotation preferred = new MethodAnnotation("java.lang.Integer", "parseInt", "(Ljava/lang/String;)I", true);

                    BugInstance bug = new BugInstance(this, "DM_BOXED_PRIMITIVE_FOR_PARSING", HIGH_PRIORITY).addClassAndMethod(this)
                            .addCalledMethod(this).addMethod(preferred).describe(MethodAnnotation.SHOULD_CALL);
                    accumulator.accumulateBug(bug, this);
                }  else if (called.getName().equals("longValue")
                        && called.getClassDescriptor().getClassName().equals("java/lang/Long")
                        && previousMethodCall.getSlashedClassName().equals("java/lang/Long")
                        && ( previousMethodCall.getName().equals("<init>")
                                && previousMethodCall.getSignature().equals("(Ljava/lang/String;)V")
                                ||  previousMethodCall.getName().equals("valueOf")
                                && previousMethodCall.getSignature().equals("(Ljava/lang/String;)Ljava/lang/Long;"))
                        ) {
                    MethodAnnotation preferred = new MethodAnnotation("java.lang.Long", "parseLong", "(Ljava/lang/String;)J", true);

                    BugInstance bug = new BugInstance(this, "DM_BOXED_PRIMITIVE_FOR_PARSING", HIGH_PRIORITY).addClassAndMethod(this)
                            .addCalledMethod(this).addMethod(preferred).describe(MethodAnnotation.SHOULD_CALL);
                    accumulator.accumulateBug(bug, this);
                }
            }
            previousMethodCall = called;
        } else
            previousMethodCall = null;
        

        if (seen == LDC || seen == LDC_W || seen == LDC2_W) {
            Constant c = getConstantRefOperand();
            if ((c instanceof ConstantInteger && ((ConstantInteger) c).getBytes() == MICROS_PER_DAY_OVERFLOWED_AS_INT
                    || c instanceof ConstantLong && ((ConstantLong) c).getBytes() == MICROS_PER_DAY_OVERFLOWED_AS_INT)) {
                BugInstance bug = new BugInstance(this, "TESTING", HIGH_PRIORITY).addClassAndMethod(this)
                        .addString("Did you mean MICROS_PER_DAY").addInt(MICROS_PER_DAY_OVERFLOWED_AS_INT)
                        .describe(IntAnnotation.INT_VALUE);
                accumulator.accumulateBug(bug, this);
            }
            if ((c instanceof ConstantInteger && ((ConstantInteger) c).getBytes() == Integer.MIN_VALUE
                    || c instanceof ConstantLong && ((ConstantLong) c).getBytes() == Long.MIN_VALUE)) {
                sawLoadOfMinValue = true;
                pendingAbsoluteValueBug = null;
                pendingAbsoluteValueBugSourceLine = null;
                absoluteValueAccumulator.clearBugs();
            }
        }


        if (seen == LCMP) {
            OpcodeStack.Item left = stack.getStackItem(1);
            OpcodeStack.Item right = stack.getStackItem(0);
            checkForCompatibleLongComparison(left, right);
            checkForCompatibleLongComparison(right, left);
        }

        if (stack.getStackDepth() >= 2)
            switch (seen) {
            case IF_ICMPEQ:
            case IF_ICMPNE:
            case IF_ICMPLE:
            case IF_ICMPGE:
            case IF_ICMPLT:
            case IF_ICMPGT:
                OpcodeStack.Item item0 = stack.getStackItem(0);
                OpcodeStack.Item item1 = stack.getStackItem(1);
                if (item0.getConstant() instanceof Integer) {
                    OpcodeStack.Item tmp = item0;
                    item0 = item1;
                    item1 = tmp;
                }
                Object constant1 = item1.getConstant();
                XMethod returnValueOf = item0.getReturnValueOf();
                if (constant1 instanceof Integer
                        && returnValueOf != null
                        && returnValueOf.getName().equals("getYear")
                        && (returnValueOf.getClassName().equals("java.util.Date") || returnValueOf.getClassName().equals(
                                "java.sql.Date"))) {
                    int year = (Integer) constant1;
                    if (year > 1900)
                        accumulator.accumulateBug(
                                new BugInstance(this, "TESTING", HIGH_PRIORITY).addClassAndMethod(this)
                                        .addString("Comparison of getYear does understand that it returns year-1900")
                                        .addMethod(returnValueOf).describe(MethodAnnotation.METHOD_CALLED).addInt(year)
                                        .describe(IntAnnotation.INT_VALUE), this);
                }
            }

        // System.out.printf("%4d %10s: %s\n", getPC(), OPCODE_NAMES[seen],
        // stack);
        if (seen == IFLT && stack.getStackDepth() > 0 && stack.getStackItem(0).getSpecialKind() == OpcodeStack.Item.SIGNED_BYTE) {
            sawCheckForNonNegativeSignedByte = getPC();
        }

        if (pendingAbsoluteValueBug != null) {
            if (opcodesSincePendingAbsoluteValueBug == 0) {
                opcodesSincePendingAbsoluteValueBug++;
            } else {
                if (seen == IREM) {
                    OpcodeStack.Item top = stack.getStackItem(0);
                    Object constantValue = top.getConstant();
                    if (constantValue instanceof Number && Util.isPowerOfTwo(((Number) constantValue).intValue())) {
                        pendingAbsoluteValueBug.setPriority(Priorities.LOW_PRIORITY);
                    }
                }
                if (false)
                    try {
                        pendingAbsoluteValueBug.addString(OPCODE_NAMES[getPrevOpcode(1)] + ":" + OPCODE_NAMES[seen] + ":"
                                + OPCODE_NAMES[getNextOpcode()]);
                    } catch (Exception e) {
                        pendingAbsoluteValueBug.addString(OPCODE_NAMES[getPrevOpcode(1)] + ":" + OPCODE_NAMES[seen]);

                    }
                absoluteValueAccumulator.accumulateBug(pendingAbsoluteValueBug, pendingAbsoluteValueBugSourceLine);
                pendingAbsoluteValueBug = null;
                pendingAbsoluteValueBugSourceLine = null;
            }
        }

        if (seen == INVOKESTATIC
                && getClassConstantOperand().equals("org/easymock/EasyMock")
                && (getNameConstantOperand().equals("replay") || getNameConstantOperand().equals("verify") || getNameConstantOperand()
                        .startsWith("reset")) && getSigConstantOperand().equals("([Ljava/lang/Object;)V")
                && getPrevOpcode(1) == ANEWARRAY && getPrevOpcode(2) == ICONST_0)
            accumulator.accumulateBug(new BugInstance(this, "DMI_VACUOUS_CALL_TO_EASYMOCK_METHOD", NORMAL_PRIORITY)
                    .addClassAndMethod(this).addCalledMethod(this), this);
        
        if (seen == INVOKESTATIC && (getClassConstantOperand().equals("com/google/common/base/Preconditions")
             && getNameConstantOperand().equals("checkNotNull")
             || getClassConstantOperand().equals("com/google/common/base/Strings")
             && (getNameConstantOperand().equals("nullToEmpty") ||
                     getNameConstantOperand().equals("emptyToNull") ||
                     getNameConstantOperand().equals("isNullOrEmpty")))
             ) {
            int args = PreorderVisitor.getNumberArguments(getSigConstantOperand());

            OpcodeStack.Item item = stack.getStackItem(args - 1);
            Object o = item.getConstant();
            if (o instanceof String) {

                OpcodeStack.Item secondArgument = null;
                String bugPattern = "DMI_DOH";
                if (args > 1) {
                    secondArgument = stack.getStackItem(args - 2);
                    Object secondConstant = secondArgument.getConstant();
                    if (!(secondConstant instanceof String)) {
                        bugPattern = "DMI_ARGUMENTS_WRONG_ORDER";
                    }
                }

                BugInstance bug = new BugInstance(this, bugPattern, NORMAL_PRIORITY).addClassAndMethod(this)
                        .addCalledMethod(this)
                        .addString("Passing String constant as value that should be null checked").describe(StringAnnotation.STRING_MESSAGE)
                        .addString((String) o).describe(StringAnnotation.STRING_CONSTANT_ROLE);
                if (secondArgument != null)
                        bug.addValueSource(secondArgument, this);

                accumulator.accumulateBug(bug, this);
            }
        }

        if (seen == INVOKESTATIC && getClassConstantOperand().equals("junit/framework/Assert")
                && getNameConstantOperand().equals("assertNotNull")) {
               int args = PreorderVisitor.getNumberArguments(getSigConstantOperand());

               OpcodeStack.Item item = stack.getStackItem(0);
               Object o = item.getConstant();
               if (o instanceof String) {

                   OpcodeStack.Item secondArgument = null;
                   String bugPattern = "DMI_DOH";
                   if (args == 2) {
                       secondArgument = stack.getStackItem(1);
                       Object secondConstant = secondArgument.getConstant();
                       if (!(secondConstant instanceof String)) {
                           bugPattern = "DMI_ARGUMENTS_WRONG_ORDER";
                       }
                   }

                   BugInstance bug = new BugInstance(this, bugPattern, NORMAL_PRIORITY).addClassAndMethod(this)
                           .addCalledMethod(this).addString("Passing String constant as value that should be null checked").describe(StringAnnotation.STRING_MESSAGE)
                           .addString((String) o).describe(StringAnnotation.STRING_CONSTANT_ROLE);
                   if (secondArgument != null)
                           bug.addValueSource(secondArgument, this);

                   accumulator.accumulateBug(bug, this);
               }
           }
        if ((seen == INVOKESTATIC || seen == INVOKEVIRTUAL || seen == INVOKESPECIAL || seen == INVOKEINTERFACE)
                && getSigConstantOperand().indexOf("Ljava/lang/Runnable;") >= 0) {
            SignatureParser parser = new SignatureParser(getSigConstantOperand());
            int count = 0;
            for (Iterator<String> i = parser.parameterSignatureIterator(); i.hasNext(); count++) {
                String parameter = i.next();
                if (parameter.equals("Ljava/lang/Runnable;")) {
                    OpcodeStack.Item item = stack.getStackItem(parser.getNumParameters() - 1 - count);
                    if ("Ljava/lang/Thread;".equals(item.getSignature())) {
                        accumulator.accumulateBug(new BugInstance(this, "DMI_THREAD_PASSED_WHERE_RUNNABLE_EXPECTED",
                                NORMAL_PRIORITY).addClassAndMethod(this).addCalledMethod(this), this);
                    }

                }
            }

        }

        if (prevOpcode == I2L && seen == INVOKESTATIC && getClassConstantOperand().equals("java/lang/Double")
                && getNameConstantOperand().equals("longBitsToDouble")) {
            accumulator.accumulateBug(new BugInstance(this, "DMI_LONG_BITS_TO_DOUBLE_INVOKED_ON_INT", HIGH_PRIORITY)
                    .addClassAndMethod(this).addCalledMethod(this), this);
        }

        if (seen == INVOKEVIRTUAL && getClassConstantOperand().equals("java/util/Random")
                && (freshRandomOnTos || freshRandomOneBelowTos)) {
            accumulator.accumulateBug(new BugInstance(this, "DMI_RANDOM_USED_ONLY_ONCE", HIGH_PRIORITY).addClassAndMethod(this)
                    .addCalledMethod(this), this);

        }

        freshRandomOneBelowTos = freshRandomOnTos && isRegisterLoad();
        freshRandomOnTos = seen == INVOKESPECIAL && getClassConstantOperand().equals("java/util/Random")
                && getNameConstantOperand().equals("<init>");

        if ((seen == INVOKEVIRTUAL && getClassConstantOperand().equals("java/util/HashMap") && getNameConstantOperand().equals(
                "get"))
                || (seen == INVOKEINTERFACE && getClassConstantOperand().equals("java/util/Map") && getNameConstantOperand()
                        .equals("get"))
                || (seen == INVOKEVIRTUAL && getClassConstantOperand().equals("java/util/HashSet") && getNameConstantOperand()
                        .equals("contains"))
                || (seen == INVOKEINTERFACE && getClassConstantOperand().equals("java/util/Set") && getNameConstantOperand()
                        .equals("contains"))) {
            OpcodeStack.Item top = stack.getStackItem(0);
            if (top.getSignature().equals("Ljava/net/URL;")) {
                accumulator.accumulateBug(new BugInstance(this, "DMI_COLLECTION_OF_URLS", HIGH_PRIORITY).addClassAndMethod(this),
                        this);
            }

        }

        /**
         * Since you can change the number of core threads for a scheduled
         * thread pool executor, disabling this for now
         */
        if (false && seen == INVOKESPECIAL
                && getClassConstantOperand().equals("java/util/concurrent/ScheduledThreadPoolExecutor")
                && getNameConstantOperand().equals("<init>")) {

            int arguments = getNumberArguments(getSigConstantOperand());
            OpcodeStack.Item item = stack.getStackItem(arguments - 1);
            Object value = item.getConstant();
            if (value instanceof Integer && ((Integer) value).intValue() == 0)
                accumulator.accumulateBug(new BugInstance(this, "DMI_SCHEDULED_THREAD_POOL_EXECUTOR_WITH_ZERO_CORE_THREADS",
                        HIGH_PRIORITY).addClassAndMethod(this), this);

        }
        if (seen == INVOKEVIRTUAL && getClassConstantOperand().equals("java/util/concurrent/ScheduledThreadPoolExecutor")
                && getNameConstantOperand().equals("setMaximumPoolSize")) {
            accumulator.accumulateBug(new BugInstance(this,
                    "DMI_FUTILE_ATTEMPT_TO_CHANGE_MAXPOOL_SIZE_OF_SCHEDULED_THREAD_POOL_EXECUTOR", HIGH_PRIORITY)
                    .addClassAndMethod(this), this);
        }
        if (isEqualsObject && !reportedBadCastInEquals) {
            if (seen == INVOKEVIRTUAL && getNameConstantOperand().equals("isInstance")
                    && getClassConstantOperand().equals("java/lang/Class")) {
                OpcodeStack.Item item = stack.getStackItem(0);
                if (item.getRegisterNumber() == 1) {
                    sawInstanceofCheck = true;
                }
            } else if (seen == INSTANCEOF || seen == INVOKEVIRTUAL && getNameConstantOperand().equals("getClass")
                    && getSigConstantOperand().equals("()Ljava/lang/Class;")) {
                OpcodeStack.Item item = stack.getStackItem(0);
                if (item.getRegisterNumber() == 1) {
                    sawInstanceofCheck = true;
                }
            } else if (seen == INVOKESPECIAL && getNameConstantOperand().equals("equals")
                    && getSigConstantOperand().equals("(Ljava/lang/Object;)Z")) {
                OpcodeStack.Item item0 = stack.getStackItem(0);
                OpcodeStack.Item item1 = stack.getStackItem(1);
                if (item1.getRegisterNumber() + item0.getRegisterNumber() == 1) {
                    sawInstanceofCheck = true;
                }
            } else if (seen == CHECKCAST && !sawInstanceofCheck) {
                OpcodeStack.Item item = stack.getStackItem(0);
                if (item.getRegisterNumber() == 1) {
                    if (getSizeOfSurroundingTryBlock(getPC()) == Integer.MAX_VALUE) {
                        accumulator.accumulateBug(new BugInstance(this, "BC_EQUALS_METHOD_SHOULD_WORK_FOR_ALL_OBJECTS",
                                NORMAL_PRIORITY).addClassAndMethod(this), this);
                    }

                    reportedBadCastInEquals = true;
                }
            }
        }
        {
            boolean foundVacuousComparison = false;
            if (seen == IF_ICMPGT || seen == IF_ICMPLE) {
                OpcodeStack.Item rhs = stack.getStackItem(0);
                Object rhsConstant = rhs.getConstant();
                if (rhsConstant instanceof Integer && ((Integer) rhsConstant).intValue() == Integer.MAX_VALUE) {
                    foundVacuousComparison = true;
                }
                OpcodeStack.Item lhs = stack.getStackItem(1);
                Object lhsConstant = lhs.getConstant();
                if (lhsConstant instanceof Integer && ((Integer) lhsConstant).intValue() == Integer.MIN_VALUE) {
                    foundVacuousComparison = true;
                }

            }
            if (seen == IF_ICMPLT || seen == IF_ICMPGE) {
                OpcodeStack.Item rhs = stack.getStackItem(0);
                Object rhsConstant = rhs.getConstant();
                if (rhsConstant instanceof Integer && ((Integer) rhsConstant).intValue() == Integer.MIN_VALUE) {
                    foundVacuousComparison = true;
                }
                OpcodeStack.Item lhs = stack.getStackItem(1);
                Object lhsConstant = lhs.getConstant();
                if (lhsConstant instanceof Integer && ((Integer) lhsConstant).intValue() == Integer.MAX_VALUE) {
                    foundVacuousComparison = true;
                }

            }
            if (foundVacuousComparison) {
                accumulator.accumulateBug(new BugInstance(this, "INT_VACUOUS_COMPARISON", getBranchOffset() < 0 ? HIGH_PRIORITY
                        : NORMAL_PRIORITY).addClassAndMethod(this), this);
            }

        }

        if (!sawLoadOfMinValue && seen == INVOKESTATIC &&
                ClassName.isMathClass(getClassConstantOperand()) && getNameConstantOperand().equals("abs")
                ) {
            OpcodeStack.Item item0 = stack.getStackItem(0);
            int special = item0.getSpecialKind();
            if (special == OpcodeStack.Item.RANDOM_INT) {
                pendingAbsoluteValueBug = new BugInstance(this, "RV_ABSOLUTE_VALUE_OF_RANDOM_INT", HIGH_PRIORITY)
                        .addClassAndMethod(this);
                pendingAbsoluteValueBugSourceLine = SourceLineAnnotation.fromVisitedInstruction(this);
                opcodesSincePendingAbsoluteValueBug = 0;
            }

            else if (special == OpcodeStack.Item.HASHCODE_INT) {
                pendingAbsoluteValueBug = new BugInstance(this, "RV_ABSOLUTE_VALUE_OF_HASHCODE", HIGH_PRIORITY)
                        .addClassAndMethod(this);
                pendingAbsoluteValueBugSourceLine = SourceLineAnnotation.fromVisitedInstruction(this);
                opcodesSincePendingAbsoluteValueBug = 0;
            }

        }

        try {
            int stackLoc = stackEntryThatMustBeNonnegative(seen);
            if (stackLoc >= 0) {
                OpcodeStack.Item tos = stack.getStackItem(stackLoc);
                switch (tos.getSpecialKind()) {
                case OpcodeStack.Item.HASHCODE_INT_REMAINDER:
                    accumulator.accumulateBug(new BugInstance(this, "RV_REM_OF_HASHCODE", HIGH_PRIORITY).addClassAndMethod(this),
                            this);

                    break;
                case OpcodeStack.Item.RANDOM_INT:
                case OpcodeStack.Item.RANDOM_INT_REMAINDER:
                    accumulator.accumulateBug(
                            new BugInstance(this, "RV_REM_OF_RANDOM_INT", HIGH_PRIORITY).addClassAndMethod(this), this);

                    break;
                }

            }
            if (seen == IREM) {
                OpcodeStack.Item item0 = stack.getStackItem(0);
                Object constant0 = item0.getConstant();
                if (constant0 instanceof Integer && ((Integer) constant0).intValue() == 1) {
                    accumulator.accumulateBug(new BugInstance(this, "INT_BAD_REM_BY_1", HIGH_PRIORITY).addClassAndMethod(this),
                            this);
                }

            }

            if (stack.getStackDepth() >= 1 && (seen == LOOKUPSWITCH || seen == TABLESWITCH)) {
                OpcodeStack.Item item0 = stack.getStackItem(0);
                if (item0.getSpecialKind() == OpcodeStack.Item.SIGNED_BYTE) {
                    int[] switchLabels = getSwitchLabels();
                    int[] switchOffsets = getSwitchOffsets();
                    for (int i = 0; i < switchLabels.length; i++) {
                        int v = switchLabels[i];
                        if (v <= -129 || v >= 128) {
                            accumulator.accumulateBug(new BugInstance(this, "INT_BAD_COMPARISON_WITH_SIGNED_BYTE", HIGH_PRIORITY)
                                    .addClassAndMethod(this).addInt(v).describe(IntAnnotation.INT_VALUE),
                                    SourceLineAnnotation.fromVisitedInstruction(this, getPC() + switchOffsets[i]));
                        }

                    }
                }
            }
            // check for use of signed byte where is it assumed it can be out of
            // the -128...127 range
            if (stack.getStackDepth() >= 2) {
                switch (seen) {
                case IF_ICMPEQ:
                case IF_ICMPNE:
                case IF_ICMPLT:
                case IF_ICMPLE:
                case IF_ICMPGE:
                case IF_ICMPGT:
                    OpcodeStack.Item item0 = stack.getStackItem(0);
                    OpcodeStack.Item item1 = stack.getStackItem(1);
                    int seen2 = seen;
                    if (item0.getConstant() != null) {
                        OpcodeStack.Item tmp = item0;
                        item0 = item1;
                        item1 = tmp;
                        switch (seen) {
                        case IF_ICMPLT:
                            seen2 = IF_ICMPGT;
                            break;
                        case IF_ICMPGE:
                            seen2 = IF_ICMPLE;
                            break;
                        case IF_ICMPGT:
                            seen2 = IF_ICMPLT;
                            break;
                        case IF_ICMPLE:
                            seen2 = IF_ICMPGE;
                            break;

                        }
                    }
                    Object constant1 = item1.getConstant();
                    if (item0.getSpecialKind() == OpcodeStack.Item.SIGNED_BYTE && constant1 instanceof Number) {
                        int v1 = ((Number) constant1).intValue();
                        if (v1 <= -129 || v1 >= 128 || v1 == 127 && !(seen2 == IF_ICMPEQ || seen2 == IF_ICMPNE

                        )) {
                            int priority = HIGH_PRIORITY;
                            if (v1 == 127) {
                                switch (seen2) {
                                case IF_ICMPGT: // 127 > x
                                    priority = LOW_PRIORITY;
                                    break;
                                case IF_ICMPGE: // 127 >= x : always true
                                    priority = NORMAL_PRIORITY;
                                    break;
                                case IF_ICMPLT: // 127 < x : never true
                                    priority = NORMAL_PRIORITY;
                                    break;
                                case IF_ICMPLE: // 127 <= x
                                    priority = LOW_PRIORITY;
                                    break;
                                }
                            } else if (v1 == 128) {
                                switch (seen2) {
                                case IF_ICMPGT: // 128 > x; always true
                                    priority = NORMAL_PRIORITY;
                                    break;
                                case IF_ICMPGE: // 128 >= x
                                    priority = HIGH_PRIORITY;
                                    break;
                                case IF_ICMPLT: // 128 < x
                                    priority = HIGH_PRIORITY;
                                    break;
                                case IF_ICMPLE: // 128 <= x; never true
                                    priority = NORMAL_PRIORITY;
                                    break;
                                }
                            } else if (v1 <= -129) {
                                priority = NORMAL_PRIORITY;
                            }

                            if (getPC() - sawCheckForNonNegativeSignedByte < 10)
                                priority++;

                            accumulator.accumulateBug(new BugInstance(this, "INT_BAD_COMPARISON_WITH_SIGNED_BYTE", priority)
                                    .addClassAndMethod(this).addInt(v1).describe(IntAnnotation.INT_VALUE), this);

                        }
                    } else if (item0.getSpecialKind() == OpcodeStack.Item.NON_NEGATIVE && constant1 instanceof Number) {
                        int v1 = ((Number) constant1).intValue();
                        if (v1 < 0) {
                            accumulator.accumulateBug(new BugInstance(this, "INT_BAD_COMPARISON_WITH_NONNEGATIVE_VALUE",
                                    HIGH_PRIORITY).addClassAndMethod(this).addInt(v1).describe(IntAnnotation.INT_VALUE), this);
                        }

                    }

                }
            }

            switch (seen) {
            case IAND:
            case LAND:
            case IOR:
            case LOR:
            case IXOR:
            case LXOR:
                long badValue = (seen == IAND || seen == LAND) ? -1 : 0;
                OpcodeStack.Item rhs = stack.getStackItem(0);
                OpcodeStack.Item lhs = stack.getStackItem(1);
                int prevOpcode = getPrevOpcode(1);
                int prevPrevOpcode = getPrevOpcode(2);
                if (rhs.hasConstantValue(badValue)
                        && (prevOpcode == LDC || prevOpcode == ICONST_0 || prevOpcode == ICONST_M1 || prevOpcode == LCONST_0)
                        && prevPrevOpcode != GOTO)
                    reportVacuousBitOperation(seen, lhs);

            }

            if (checkForBitIorofSignedByte && seen != I2B) {
                String pattern = (prevOpcode == LOR || prevOpcode == IOR) ? "BIT_IOR_OF_SIGNED_BYTE" : "BIT_ADD_OF_SIGNED_BYTE";
                int priority = (prevOpcode == LOR || prevOpcode == LADD) ? HIGH_PRIORITY : NORMAL_PRIORITY;
                accumulator.accumulateBug(new BugInstance(this, pattern, priority).addClassAndMethod(this), this);

                checkForBitIorofSignedByte = false;
            } else if ((seen == IOR || seen == LOR || seen == IADD || seen == LADD) && stack.getStackDepth() >= 2) {
                OpcodeStack.Item item0 = stack.getStackItem(0);
                OpcodeStack.Item item1 = stack.getStackItem(1);

                int special0 = item0.getSpecialKind();
                int special1 = item1.getSpecialKind();
                if (special0 == OpcodeStack.Item.SIGNED_BYTE && special1 == OpcodeStack.Item.LOW_8_BITS_CLEAR
                        && !item1.hasConstantValue(256) || special0 == OpcodeStack.Item.LOW_8_BITS_CLEAR
                        && !item0.hasConstantValue(256) && special1 == OpcodeStack.Item.SIGNED_BYTE) {
                    checkForBitIorofSignedByte = true;
                } else {
                    checkForBitIorofSignedByte = false;
                }
            } else {
                checkForBitIorofSignedByte = false;
            }

            if (prevOpcodeWasReadLine && sinceBufferedInputStreamReady >= 100 && seen == INVOKEVIRTUAL
                    && getClassConstantOperand().equals("java/lang/String") && getSigConstantOperand().startsWith("()")) {
                accumulator.accumulateBug(
                        new BugInstance(this, "NP_IMMEDIATE_DEREFERENCE_OF_READLINE", NORMAL_PRIORITY).addClassAndMethod(this),
                        this);
            }

            if (seen == INVOKEVIRTUAL && getClassConstantOperand().equals("java/io/BufferedReader")
                    && getNameConstantOperand().equals("ready") && getSigConstantOperand().equals("()Z")) {
                sinceBufferedInputStreamReady = 0;
            } else {
                sinceBufferedInputStreamReady++;
            }

            prevOpcodeWasReadLine = (seen == INVOKEVIRTUAL || seen == INVOKEINTERFACE)
                    && getNameConstantOperand().equals("readLine") && getSigConstantOperand().equals("()Ljava/lang/String;");

            // System.out.println(randomNextIntState + " " + OPCODE_NAMES[seen]
            // + " " + getMethodName());
            switch (randomNextIntState) {
            case 0:
                if (seen == INVOKEVIRTUAL && getClassConstantOperand().equals("java/util/Random")
                        && getNameConstantOperand().equals("nextDouble") || seen == INVOKESTATIC
                        && ClassName.isMathClass(getClassConstantOperand()) && getNameConstantOperand().equals("random")) {
                    randomNextIntState = 1;
                }
                break;
            case 1:
                if (seen == D2I) {
                    accumulator.accumulateBug(new BugInstance(this, "RV_01_TO_INT", HIGH_PRIORITY).addClassAndMethod(this), this);
                    randomNextIntState = 0;
                } else if (seen == DMUL)
                    randomNextIntState = 4;
                else if (seen == LDC2_W && getConstantRefOperand() instanceof ConstantDouble
                        && ((ConstantDouble) getConstantRefOperand()).getBytes() == Integer.MIN_VALUE)
                    randomNextIntState = 0;
                else
                    randomNextIntState = 2;

                break;
            case 2:
                if (seen == I2D) {
                    randomNextIntState = 3;
                } else if (seen == DMUL) {
                    randomNextIntState = 4;
                } else {
                    randomNextIntState = 0;
                }
                break;
            case 3:
                if (seen == DMUL) {
                    randomNextIntState = 4;
                } else {
                    randomNextIntState = 0;
                }
                break;
            case 4:
                if (seen == D2I) {
                    accumulator.accumulateBug(
                            new BugInstance(this, "DM_NEXTINT_VIA_NEXTDOUBLE", NORMAL_PRIORITY).addClassAndMethod(this), this);
                }
                randomNextIntState = 0;
                break;
            default:
                throw new IllegalStateException();
            }
            if (isPublicStaticVoidMain
                    && seen == INVOKEVIRTUAL
                    && getClassConstantOperand().startsWith("javax/swing/")
                    && (getNameConstantOperand().equals("show") && getSigConstantOperand().equals("()V")
                            || getNameConstantOperand().equals("pack") && getSigConstantOperand().equals("()V") || getNameConstantOperand()
                            .equals("setVisible") && getSigConstantOperand().equals("(Z)V"))) {
                accumulator.accumulateBug(
                        new BugInstance(this, "SW_SWING_METHODS_INVOKED_IN_SWING_THREAD", LOW_PRIORITY).addClassAndMethod(this),
                        this);
            }

            // if ((seen == INVOKEVIRTUAL)
            // && getClassConstantOperand().equals("java/lang/String")
            // && getNameConstantOperand().equals("substring")
            // && getSigConstantOperand().equals("(I)Ljava/lang/String;")
            // && stack.getStackDepth() > 1) {
            // OpcodeStack.Item item = stack.getStackItem(0);
            // Object o = item.getConstant();
            // if (o != null && o instanceof Integer) {
            // int v = ((Integer) o).intValue();
            // if (v == 0)
            // accumulator.accumulateBug(new BugInstance(this,
            // "DMI_USELESS_SUBSTRING", NORMAL_PRIORITY)
            // .addClassAndMethod(this)
            // .addSourceLine(this));
            // }
            // }

            if ((seen == INVOKEVIRTUAL) && getNameConstantOperand().equals("isAnnotationPresent")
                    && getSigConstantOperand().equals("(Ljava/lang/Class;)Z") && stack.getStackDepth() > 0) {
                OpcodeStack.Item item = stack.getStackItem(0);
                Object value = item.getConstant();
                if (value instanceof String) {
                    String annotationClassName = (String) value;
                    boolean lacksClassfileRetention = AnalysisContext.currentAnalysisContext().getAnnotationRetentionDatabase()
                            .lacksRuntimeRetention(annotationClassName.replace('/', '.'));
                    if (lacksClassfileRetention) {
                        ClassDescriptor annotationClass = DescriptorFactory.createClassDescriptor(annotationClassName);
                        accumulator.accumulateBug(
                                new BugInstance(this, "DMI_ANNOTATION_IS_NOT_VISIBLE_TO_REFLECTION", HIGH_PRIORITY)
                                        .addClassAndMethod(this).addCalledMethod(this).addClass(annotationClass)
                                        .describe(ClassAnnotation.ANNOTATION_ROLE), this);
                    }

                }

            }
            if ((seen == INVOKEVIRTUAL) && getNameConstantOperand().equals("next")
                    && getSigConstantOperand().equals("()Ljava/lang/Object;") && getMethodName().equals("hasNext")
                    && getMethodSig().equals("()Z") && stack.getStackDepth() > 0) {
                OpcodeStack.Item item = stack.getStackItem(0);

                accumulator.accumulateBug(new BugInstance(this, "DMI_CALLING_NEXT_FROM_HASNEXT", item.isInitialParameter()
                        && item.getRegisterNumber() == 0 ? NORMAL_PRIORITY : LOW_PRIORITY).addClassAndMethod(this)
                        .addCalledMethod(this), this);

            }

            if ((seen == INVOKESPECIAL) && getClassConstantOperand().equals("java/lang/String")
                    && getNameConstantOperand().equals("<init>") && getSigConstantOperand().equals("(Ljava/lang/String;)V")
                    && !Subtypes2.isJSP(getThisClass())) {

                accumulator.accumulateBug(new BugInstance(this, "DM_STRING_CTOR", NORMAL_PRIORITY).addClassAndMethod(this), this);

            }

            if (seen == INVOKESTATIC && getClassConstantOperand().equals("java/lang/System")
                    && getNameConstantOperand().equals("runFinalizersOnExit") || seen == INVOKEVIRTUAL
                    && getClassConstantOperand().equals("java/lang/Runtime")
                    && getNameConstantOperand().equals("runFinalizersOnExit")) {
                accumulator.accumulateBug(
                        new BugInstance(this, "DM_RUN_FINALIZERS_ON_EXIT", HIGH_PRIORITY).addClassAndMethod(this), this);
            }

            if ((seen == INVOKESPECIAL) && getClassConstantOperand().equals("java/lang/String")
                    && getNameConstantOperand().equals("<init>") && getSigConstantOperand().equals("()V")) {

                accumulator.accumulateBug(new BugInstance(this, "DM_STRING_VOID_CTOR", NORMAL_PRIORITY).addClassAndMethod(this),
                        this);

            }

            if (!isPublicStaticVoidMain && seen == INVOKESTATIC && getClassConstantOperand().equals("java/lang/System")
                    && getNameConstantOperand().equals("exit") && !getMethodName().equals("processWindowEvent")
                    && !getMethodName().startsWith("windowClos") && getMethodName().indexOf("exit") == -1
                    && getMethodName().indexOf("Exit") == -1 && getMethodName().indexOf("crash") == -1
                    && getMethodName().indexOf("Crash") == -1 && getMethodName().indexOf("die") == -1
                    && getMethodName().indexOf("Die") == -1 && getMethodName().indexOf("main") == -1) {
                accumulator.accumulateBug(new BugInstance(this, "DM_EXIT", getMethod().isStatic() ? LOW_PRIORITY
                        : NORMAL_PRIORITY).addClassAndMethod(this), SourceLineAnnotation.fromVisitedInstruction(this));
            }
            if (((seen == INVOKESTATIC && getClassConstantOperand().equals("java/lang/System")) || (seen == INVOKEVIRTUAL && getClassConstantOperand()
                    .equals("java/lang/Runtime")))
                    && getNameConstantOperand().equals("gc")
                    && getSigConstantOperand().equals("()V")
                    && !getDottedClassName().startsWith("java.lang")
                    && !getMethodName().startsWith("gc") && !getMethodName().endsWith("gc")) {
                if (gcInvocationBugReport == null) {
                    // System.out.println("Saw call to GC");
                    if (isPublicStaticVoidMain) {
                        // System.out.println("Skipping GC complaint in main method");
                        return;
                    }
                    if (isTestMethod(getMethod())) {
                        return;
                    }
                    // Just save this report in a field; it will be flushed
                    // IFF there were no calls to System.currentTimeMillis();
                    // in the method.
                    gcInvocationBugReport = new BugInstance(this, "DM_GC", HIGH_PRIORITY).addClassAndMethod(this).addSourceLine(
                            this);
                    gcInvocationPC = getPC();
                    // System.out.println("GC invocation at pc " + PC);
                }
            }
            if (!isSynthetic && (seen == INVOKESPECIAL) && getClassConstantOperand().equals("java/lang/Boolean")
                    && getNameConstantOperand().equals("<init>") && !getClassName().equals("java/lang/Boolean")) {
                int majorVersion = getThisClass().getMajor();
                if (majorVersion >= MAJOR_1_4) {
                    accumulator.accumulateBug(new BugInstance(this, "DM_BOOLEAN_CTOR", NORMAL_PRIORITY).addClassAndMethod(this),
                            this);
                }

            }
            if ((seen == INVOKESTATIC) && getClassConstantOperand().equals("java/lang/System")
                    && (getNameConstantOperand().equals("currentTimeMillis") || getNameConstantOperand().equals("nanoTime"))) {
                sawCurrentTimeMillis = true;
            }
            if ((seen == INVOKEVIRTUAL) && getClassConstantOperand().equals("java/lang/String")
                    && getNameConstantOperand().equals("toString") && getSigConstantOperand().equals("()Ljava/lang/String;")) {

                accumulator
                        .accumulateBug(new BugInstance(this, "DM_STRING_TOSTRING", LOW_PRIORITY).addClassAndMethod(this), this);

            }

            if ((seen == INVOKEVIRTUAL) && getClassConstantOperand().equals("java/lang/String")
                    && (getNameConstantOperand().equals("toUpperCase") || getNameConstantOperand().equals("toLowerCase"))
                    && getSigConstantOperand().equals("()Ljava/lang/String;")) {

                accumulator.accumulateBug(new BugInstance(this, "DM_CONVERT_CASE", LOW_PRIORITY).addClassAndMethod(this), this);

            }

            if ((seen == INVOKESPECIAL) && getNameConstantOperand().equals("<init>")) {
                String cls = getClassConstantOperand();
                String sig = getSigConstantOperand();
                String primitiveType = ClassName.getPrimitiveType(cls);
                if (primitiveType != null && sig.charAt(1) == primitiveType.charAt(0)) {
                    primitiveObjCtorSeen = cls;
                } else {
                    primitiveObjCtorSeen = null;
                }
            } else if ((primitiveObjCtorSeen != null) && (seen == INVOKEVIRTUAL) && getNameConstantOperand().equals("toString")
                    && getClassConstantOperand().equals(primitiveObjCtorSeen)
                    && getSigConstantOperand().equals("()Ljava/lang/String;")) {
                BugInstance bug = new BugInstance(this, "DM_BOXED_PRIMITIVE_TOSTRING", NORMAL_PRIORITY).addClassAndMethod(this).addCalledMethod(this);
                MethodAnnotation preferred = new MethodAnnotation(ClassName.toDottedClassName(primitiveObjCtorSeen),
                        "toString", "("+ClassName.getPrimitiveType(primitiveObjCtorSeen)+")Ljava/lang/String;", true);
                bug.addMethod(preferred).describe(MethodAnnotation.SHOULD_CALL);
                accumulator.accumulateBug(
                        bug, this);

                primitiveObjCtorSeen = null;
            } else {
                primitiveObjCtorSeen = null;
            }

            if ((seen == INVOKESPECIAL) && getNameConstantOperand().equals("<init>")) {
                ctorSeen = true;
            } else if (ctorSeen && (seen == INVOKEVIRTUAL) && getClassConstantOperand().equals("java/lang/Object")
                    && getNameConstantOperand().equals("getClass") && getSigConstantOperand().equals("()Ljava/lang/Class;")) {
                accumulator.accumulateBug(new BugInstance(this, "DM_NEW_FOR_GETCLASS", NORMAL_PRIORITY).addClassAndMethod(this),
                        this);
                ctorSeen = false;
            } else {
                ctorSeen = false;
            }

            if (jdk15ChecksEnabled && (seen == INVOKEVIRTUAL) && isMonitorWait(getNameConstantOperand(), getSigConstantOperand())) {
                checkMonitorWait();
            }

            if ((seen == INVOKESPECIAL) && getNameConstantOperand().equals("<init>")
                    && getClassConstantOperand().equals("java/lang/Thread")) {
                String sig = getSigConstantOperand();
                if (sig.equals("()V") || sig.equals("(Ljava/lang/String;)V")
                        || sig.equals("(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")) {
                    OpcodeStack.Item invokedOn = stack.getItemMethodInvokedOn(this);
                    if (!getMethodName().equals("<init>") || invokedOn.getRegisterNumber() != 0) {
                        accumulator.accumulateBug(
                                new BugInstance(this, "DM_USELESS_THREAD", LOW_PRIORITY).addClassAndMethod(this), this);

                    }
                }
            }

            if (seen == INVOKESPECIAL && getClassConstantOperand().equals("java/math/BigDecimal")
                    && getNameConstantOperand().equals("<init>") && getSigConstantOperand().equals("(D)V")) {
                OpcodeStack.Item top = stack.getStackItem(0);
                Object value = top.getConstant();
                if (value instanceof Double) {
                    double arg = ((Double) value).doubleValue();
                    String dblString = Double.toString(arg);
                    String bigDecimalString = new BigDecimal(arg).toString();
                    boolean ok = dblString.equals(bigDecimalString) || dblString.equals(bigDecimalString + ".0");

                    if (!ok) {
                        boolean scary = dblString.length() <= 8 && bigDecimalString.length() > 12
                                && dblString.toUpperCase().indexOf("E") == -1;
                        bugReporter.reportBug(new BugInstance(this, "DMI_BIGDECIMAL_CONSTRUCTED_FROM_DOUBLE",
                                scary ? NORMAL_PRIORITY : LOW_PRIORITY).addClassAndMethod(this).addCalledMethod(this)
                                .addMethod("java.math.BigDecimal", "valueOf", "(D)Ljava/math/BigDecimal;", true)
                                .describe(MethodAnnotation.METHOD_ALTERNATIVE_TARGET).addString(dblString)
                                .addString(bigDecimalString).addSourceLine(this));
                    }
                }

            }

        } finally {
            prevOpcode = seen;
        }
    }

    private void checkForCompatibleLongComparison(OpcodeStack.Item left, OpcodeStack.Item right) {
        if (left.getSpecialKind() == Item.RESULT_OF_I2L && right.getConstant() != null) {
            long value = ((Number) right.getConstant()).longValue();
            if ( (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE)) {
                int priority  = Priorities.HIGH_PRIORITY;
                if (value == Integer.MAX_VALUE+1 || value == Integer.MIN_VALUE -1)
                    priority = Priorities.NORMAL_PRIORITY;
                String stringValue = IntAnnotation.getShortInteger(value)+"L";
                if (value == 0xffffffffL)
                    stringValue = "0xffffffffL";
                else if (value == 0x80000000L)
                    stringValue = "0x80000000L";
                accumulator.accumulateBug(new BugInstance(this, "INT_BAD_COMPARISON_WITH_INT_VALUE", priority ).addClassAndMethod(this)
                        .addString(stringValue).describe(StringAnnotation.STRING_NONSTRING_CONSTANT_ROLE)
                        .addValueSource(left, this) , this);
            }
        }
    }

    /**
     * @param seen
     * @param item
     */
    private void reportVacuousBitOperation(int seen, OpcodeStack.Item item) {
        if (item.getConstant() == null)
            accumulator
                    .accumulateBug(
                            new BugInstance(this, "INT_VACUOUS_BIT_OPERATION", NORMAL_PRIORITY)
                                    .addClassAndMethod(this)
                                    .addString(OPCODE_NAMES[seen])
                                    .addOptionalAnnotation(
                                            LocalVariableAnnotation.getLocalVariableAnnotation(getMethod(), item, getPC())), this);
    }

    /**
     * Return index of stack entry that must be nonnegative.
     *
     * Return -1 if no stack entry is required to be nonnegative.
     */
    private int stackEntryThatMustBeNonnegative(int seen) {
        switch (seen) {
        case INVOKEINTERFACE:
            if (getClassConstantOperand().equals("java/util/List")) {
                return getStackEntryOfListCallThatMustBeNonnegative();
            }
            break;
        case INVOKEVIRTUAL:
            if (getClassConstantOperand().equals("java/util/LinkedList")
                    || getClassConstantOperand().equals("java/util/ArrayList")) {
                return getStackEntryOfListCallThatMustBeNonnegative();
            }
            break;

        case IALOAD:
        case AALOAD:
        case SALOAD:
        case CALOAD:
        case BALOAD:
        case LALOAD:
        case DALOAD:
        case FALOAD:
            return 0;
        case IASTORE:
        case AASTORE:
        case SASTORE:
        case CASTORE:
        case BASTORE:
        case LASTORE:
        case DASTORE:
        case FASTORE:
            return 1;
        }
        return -1;
    }

    private int getStackEntryOfListCallThatMustBeNonnegative() {
        String name = getNameConstantOperand();
        if ((name.equals("add") || name.equals("set")) && getSigConstantOperand().startsWith("(I")) {
            return 1;
        }
        if ((name.equals("get") || name.equals("remove")) && getSigConstantOperand().startsWith("(I)")) {
            return 0;
        }
        return -1;
    }

    private void checkMonitorWait() {
        try {
            TypeDataflow typeDataflow = getClassContext().getTypeDataflow(getMethod());
            TypeDataflow.LocationAndFactPair pair = typeDataflow.getLocationAndFactForInstruction(getPC());

            if (pair == null) {
                return;
            }

            Type receiver = pair.frame.getInstance(pair.location.getHandle().getInstruction(), getClassContext()
                    .getConstantPoolGen());

            if (!(receiver instanceof ReferenceType)) {
                return;
            }

            if (Hierarchy.isSubtype((ReferenceType) receiver, CONDITION_TYPE)) {
                accumulator.accumulateBug(
                        new BugInstance(this, "DM_MONITOR_WAIT_ON_CONDITION", HIGH_PRIORITY).addClassAndMethod(this), this);

            }
        } catch (ClassNotFoundException e) {
            bugReporter.reportMissingClass(e);
        } catch (DataflowAnalysisException e) {
            bugReporter.logError("Exception caught by DumbMethods", e);
        } catch (CFGBuilderException e) {
            bugReporter.logError("Exception caught by DumbMethods", e);
        }
    }

    private boolean isMonitorWait(String name, String sig) {
        // System.out.println("Check call " + name + "," + sig);
        return name.equals("wait") && (sig.equals("()V") || sig.equals("(J)V") || sig.equals("(JI)V"));
    }

    @Override
    public void visit(Code obj) {

        super.visit(obj);
        flush();
    }

    /**
     * A heuristic - how long a catch block for OutOfMemoryError might be.
     */
    private static final int OOM_CATCH_LEN = 20;

    /**
     * Flush out cached state at the end of a method.
     */
    private void flush() {
        
        if (pendingAbsoluteValueBug != null) {
            absoluteValueAccumulator.accumulateBug(pendingAbsoluteValueBug, pendingAbsoluteValueBugSourceLine);
            pendingAbsoluteValueBug = null;
            pendingAbsoluteValueBugSourceLine = null;
        }
        accumulator.reportAccumulatedBugs();
        if (sawLoadOfMinValue)
            absoluteValueAccumulator.clearBugs();
        else
            absoluteValueAccumulator.reportAccumulatedBugs();
        if (gcInvocationBugReport != null && !sawCurrentTimeMillis) {
            // Make sure the GC invocation is not in an exception handler
            // for OutOfMemoryError.
            boolean outOfMemoryHandler = false;
            for (CodeException handler : exceptionTable) {
                if (gcInvocationPC < handler.getHandlerPC() || gcInvocationPC > handler.getHandlerPC() + OOM_CATCH_LEN) {
                    continue;
                }
                int catchTypeIndex = handler.getCatchType();
                if (catchTypeIndex > 0) {
                    ConstantPool cp = getThisClass().getConstantPool();
                    Constant constant = cp.getConstant(catchTypeIndex);
                    if (constant instanceof ConstantClass) {
                        String exClassName = (String) ((ConstantClass) constant).getConstantValue(cp);
                        if (exClassName.equals("java/lang/OutOfMemoryError")) {
                            outOfMemoryHandler = true;
                            break;
                        }
                    }
                }
            }

            if (!outOfMemoryHandler) {
                bugReporter.reportBug(gcInvocationBugReport);
            }
        }

        sawCurrentTimeMillis = false;
        gcInvocationBugReport = null;

        exceptionTable = null;
    }
}
