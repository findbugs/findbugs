package edu.umd.cs.findbugs.detect;

import java.io.File;
import java.util.Iterator;

import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.MethodGen;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.Detector;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.ba.CFG;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.MethodUnprofitableException;
import edu.umd.cs.findbugs.ba.constant.Constant;
import edu.umd.cs.findbugs.ba.constant.ConstantDataflow;
import edu.umd.cs.findbugs.ba.constant.ConstantFrame;

public class DumbMethodInvocations implements Detector {

    private final BugReporter bugReporter;

    private final BugAccumulator bugAccumulator;

    public DumbMethodInvocations(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
        this.bugAccumulator = new BugAccumulator(bugReporter);
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        Method[] methodList = classContext.getJavaClass().getMethods();

        for (Method method : methodList) {
            if (method.getCode() == null)
                continue;

            try {
                analyzeMethod(classContext, method);
                bugAccumulator.reportAccumulatedBugs();
            } catch (MethodUnprofitableException mue) {
                if (SystemProperties.getBoolean("unprofitable.debug")) // otherwise
                                                                       // don't
                                                                       // report
                    bugReporter.logError("skipping unprofitable method in " + getClass().getName());
            } catch (CFGBuilderException e) {
                bugReporter.logError("Detector " + this.getClass().getName() + " caught exception", e);
            } catch (DataflowAnalysisException e) {
                bugReporter.logError("Detector " + this.getClass().getName() + " caught exception", e);
            }
        }
    }

    private void analyzeMethod(ClassContext classContext, Method method) throws CFGBuilderException, DataflowAnalysisException {
        CFG cfg = classContext.getCFG(method);
        ConstantDataflow constantDataflow = classContext.getConstantDataflow(method);
        ConstantPoolGen cpg = classContext.getConstantPoolGen();
        MethodGen methodGen = classContext.getMethodGen(method);
        String sourceFile = classContext.getJavaClass().getSourceFileName();

        for (Iterator<Location> i = cfg.locationIterator(); i.hasNext();) {
            Location location = i.next();

            Instruction ins = location.getHandle().getInstruction();
            if (!(ins instanceof InvokeInstruction))
                continue;
            InvokeInstruction iins = (InvokeInstruction) ins;

            ConstantFrame frame = constantDataflow.getFactAtLocation(location);
            if (!frame.isValid()) {
                // This basic block is probably dead
                continue;
            }

            if (iins.getName(cpg).equals("getConnection")
                    && iins.getSignature(cpg).equals(
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/sql/Connection;")
                    && iins.getClassName(cpg).equals("java.sql.DriverManager")) {
                Constant operandValue = frame.getTopValue();
                if (operandValue.isConstantString()) {
                    String password = operandValue.getConstantString();
                    if (password.length() == 0)
                        bugAccumulator.accumulateBug(new BugInstance(this, "DMI_EMPTY_DB_PASSWORD", NORMAL_PRIORITY)
                                .addClassAndMethod(methodGen, sourceFile), classContext, methodGen, sourceFile, location);
                    else
                        bugAccumulator.accumulateBug(new BugInstance(this, "DMI_CONSTANT_DB_PASSWORD", NORMAL_PRIORITY)
                                .addClassAndMethod(methodGen, sourceFile), classContext, methodGen, sourceFile, location);

                }
            }

            if (iins.getName(cpg).equals("substring") && iins.getSignature(cpg).equals("(I)Ljava/lang/String;")
                    && iins.getClassName(cpg).equals("java.lang.String")) {

                Constant operandValue = frame.getTopValue();
                if (!operandValue.isConstantInteger())
                    continue;
                int v = operandValue.getConstantInt();
                if (v == 0)
                    bugAccumulator.accumulateBug(new BugInstance(this, "DMI_USELESS_SUBSTRING", NORMAL_PRIORITY)
                            .addClassAndMethod(methodGen, sourceFile), classContext, methodGen, sourceFile, location);

            } else if (iins.getName(cpg).equals("<init>") && iins.getSignature(cpg).equals("(Ljava/lang/String;)V")
                    && iins.getClassName(cpg).equals("java.io.File")) {

                Constant operandValue = frame.getTopValue();
                if (!operandValue.isConstantString())
                    continue;
                String v = operandValue.getConstantString();
                if (isAbsoluteFileName(v) && !v.startsWith("/etc/") && !v.startsWith("/dev/")
                        && !v.startsWith("/proc")) {
                    int priority = NORMAL_PRIORITY;
                    if (v.startsWith("/tmp"))
                        priority = LOW_PRIORITY;
                    else if (v.indexOf("/home") >= 0)
                        priority = HIGH_PRIORITY;
                    bugAccumulator.accumulateBug(new BugInstance(this, "DMI_HARDCODED_ABSOLUTE_FILENAME", priority)
                            .addClassAndMethod(methodGen, sourceFile).addString(v).describe("FILE_NAME"), classContext,
                            methodGen, sourceFile, location);
                }

            }

        }
    }

    private boolean isAbsoluteFileName(String v) {
        if (v.startsWith("/dev/"))
            return false;
        if (v.startsWith("/"))
            return true;
        if (v.startsWith("C:"))
            return true;
        if (v.startsWith("c:"))
            return true;
        try {
            File f = new File(v);
            return f.isAbsolute();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void report() {
    }

}
