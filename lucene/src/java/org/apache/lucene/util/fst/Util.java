package org.apache.lucene.util.fst;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.*;
import java.util.*;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRef;

/** Static helper methods
 *
 * @lucene.experimental */
public final class Util {
  private Util() {
  }

  /** Looks up the output for this input, or null if the
   *  input is not accepted. */
  public static<T> T get(FST<T> fst, IntsRef input) throws IOException {

    // TODO: would be nice not to alloc this on every lookup
    final FST.Arc<T> arc = fst.getFirstArc(new FST.Arc<T>());

    // Accumulate output as we go
    final T NO_OUTPUT = fst.outputs.getNoOutput();
    T output = NO_OUTPUT;
    for(int i=0;i<input.length;i++) {
      if (fst.findTargetArc(input.ints[input.offset + i], arc, arc) == null) {
        return null;
      } else if (arc.output != NO_OUTPUT) {
        output = fst.outputs.add(output, arc.output);
      }
    }

    if (fst.findTargetArc(FST.END_LABEL, arc, arc) == null) {
      return null;
    } else if (arc.output != NO_OUTPUT) {
      return fst.outputs.add(output, arc.output);
    } else {
      return output;
    }
  }

  // TODO: maybe a CharsRef version for BYTE2

  /** Looks up the output for this input, or null if the
   *  input is not accepted */
  public static<T> T get(FST<T> fst, BytesRef input) throws IOException {
    assert fst.inputType == FST.INPUT_TYPE.BYTE1;

    // TODO: would be nice not to alloc this on every lookup
    final FST.Arc<T> arc = fst.getFirstArc(new FST.Arc<T>());

    // Accumulate output as we go
    final T NO_OUTPUT = fst.outputs.getNoOutput();
    T output = NO_OUTPUT;
    for(int i=0;i<input.length;i++) {
      if (fst.findTargetArc(input.bytes[i+input.offset] & 0xFF, arc, arc) == null) {
        return null;
      } else if (arc.output != NO_OUTPUT) {
        output = fst.outputs.add(output, arc.output);
      }
    }

    if (fst.findTargetArc(FST.END_LABEL, arc, arc) == null) {
      return null;
    } else if (arc.output != NO_OUTPUT) {
      return fst.outputs.add(output, arc.output);
    } else {
      return output;
    }
  }
  
  /**
   * Dumps an {@link FST} to a GraphViz's <code>dot</code> language description
   * for visualization. Example of use:
   * 
   * <pre>
   * PrintStream ps = new PrintStream(&quot;out.dot&quot;);
   * fst.toDot(ps);
   * ps.close();
   * </pre>
   * 
   * and then, from command line:
   * 
   * <pre>
   * dot -Tpng -o out.png out.dot
   * </pre>
   * 
   * <p>
   * Note: larger FSTs (a few thousand nodes) won't even render, don't bother.
   * 
   * @param sameRank
   *          If <code>true</code>, the resulting <code>dot</code> file will try
   *          to order states in layers of breadth-first traversal. This may
   *          mess up arcs, but makes the output FST's structure a bit clearer.
   * 
   * @param labelStates
   *          If <code>true</code> states will have labels equal to their offsets in their
   *          binary format. Expands the graph considerably. 
   * 
   * @see "http://www.graphviz.org/"
   */
  public static <T> void toDot(FST<T> fst, Writer out, boolean sameRank, boolean labelStates) 
    throws IOException {    
    final String expandedNodeColor = "blue";

    // This is the start arc in the automaton (from the epsilon state to the first state 
    // with outgoing transitions.
    final FST.Arc<T> startArc = fst.getFirstArc(new FST.Arc<T>());

    // A queue of transitions to consider for the next level.
    final List<FST.Arc<T>> thisLevelQueue = new ArrayList<FST.Arc<T>>();

    // A queue of transitions to consider when processing the next level.
    final List<FST.Arc<T>> nextLevelQueue = new ArrayList<FST.Arc<T>>();
    nextLevelQueue.add(startArc);
    
    // A list of states on the same level (for ranking).
    final List<Integer> sameLevelStates = new ArrayList<Integer>();

    // A bitset of already seen states (target offset).
    final BitSet seen = new BitSet();
    seen.set(startArc.target);

    // Shape for states.
    final String stateShape = "circle";
    final String finalStateShape = "doublecircle";

    // Emit DOT prologue.
    out.write("digraph FST {\n");
    out.write("  rankdir = LR; splines=true; concentrate=true; ordering=out; ranksep=2.5; \n");

    if (!labelStates) {
      out.write("  node [shape=circle, width=.2, height=.2, style=filled]\n");      
    }

    emitDotState(out, "initial", "point", "white", "");

    final T NO_OUTPUT = fst.outputs.getNoOutput();

    // final FST.Arc<T> scratchArc = new FST.Arc<T>();

    {
      final String stateColor;
      if (fst.isExpandedTarget(startArc)) {
        stateColor = expandedNodeColor;
      } else {
        stateColor = null;
      }

      final boolean isFinal;
      final T finalOutput;
      if (startArc.isFinal()) {
        isFinal = true;
        finalOutput = startArc.nextFinalOutput == NO_OUTPUT ? null : startArc.nextFinalOutput;
      } else {
        isFinal = false;
        finalOutput = null;
      }
      
      emitDotState(out, Integer.toString(startArc.target), isFinal ? finalStateShape : stateShape, stateColor, finalOutput == null ? "" : fst.outputs.outputToString(finalOutput));
    }

    out.write("  initial -> " + startArc.target + "\n");

    int level = 0;

    while (!nextLevelQueue.isEmpty()) {
      // we could double buffer here, but it doesn't matter probably.
      thisLevelQueue.addAll(nextLevelQueue);
      nextLevelQueue.clear();

      level++;
      out.write("\n  // Transitions and states at level: " + level + "\n");
      while (!thisLevelQueue.isEmpty()) {
        final FST.Arc<T> arc = thisLevelQueue.remove(thisLevelQueue.size() - 1);
        if (fst.targetHasArcs(arc)) {
          // scan all arcs
          final int node = arc.target;
          fst.readFirstTargetArc(arc, arc);

          if (arc.label == FST.END_LABEL) {
            // Skip it -- prior recursion took this into account already
            assert !arc.isLast();
            fst.readNextArc(arc);
          }

          while (true) {

            // Emit the unseen state and add it to the queue for the next level.
            if (arc.target >= 0 && !seen.get(arc.target)) {

              /*
              boolean isFinal = false;
              T finalOutput = null;
              fst.readFirstTargetArc(arc, scratchArc);
              if (scratchArc.isFinal() && fst.targetHasArcs(scratchArc)) {
                // target is final
                isFinal = true;
                finalOutput = scratchArc.output == NO_OUTPUT ? null : scratchArc.output;
                System.out.println("dot hit final label=" + (char) scratchArc.label);
              }
              */
              final String stateColor;
              if (fst.isExpandedTarget(arc)) {
                stateColor = expandedNodeColor;
              } else {
               stateColor = null;
              }

              final String finalOutput;
              if (arc.nextFinalOutput != null && arc.nextFinalOutput != NO_OUTPUT) {
                finalOutput = fst.outputs.outputToString(arc.nextFinalOutput);
              } else {
                finalOutput = "";
              }

              emitDotState(out, Integer.toString(arc.target), arc.isFinal() ? finalStateShape : stateShape, stateColor, finalOutput);
              seen.set(arc.target);
              nextLevelQueue.add(new FST.Arc<T>().copyFrom(arc));
              sameLevelStates.add(arc.target);
            }

            String outs;
            if (arc.output != NO_OUTPUT) {
              outs = "/" + fst.outputs.outputToString(arc.output);
            } else {
              outs = "";
            }

            if (!fst.targetHasArcs(arc) && arc.isFinal() && arc.nextFinalOutput != NO_OUTPUT) {
              // Tricky special case: sometimes, due to
              // pruning, the builder can [sillily] produce
              // an FST with an arc into the final end state
              // (-1) but also with a next final output; in
              // this case we pull that output up onto this
              // arc
              outs = outs + "/[" + fst.outputs.outputToString(arc.nextFinalOutput) + "]";
            }

            assert arc.label != FST.END_LABEL;
            out.write("  " + node + " -> " + arc.target + " [label=\"" + printableLabel(arc.label) + outs + "\"]\n");
                   
            // Break the loop if we're on the last arc of this state.
            if (arc.isLast()) {
              break;
            }
            fst.readNextArc(arc);
          }
        }
      }

      // Emit state ranking information.
      if (sameRank && sameLevelStates.size() > 1) {
        out.write("  {rank=same; ");
        for (int state : sameLevelStates) {
          out.write(state + "; ");
        }
        out.write(" }\n");
      }
      sameLevelStates.clear();                
    }

    // Emit terminating state (always there anyway).
    out.write("  -1 [style=filled, color=black, shape=doublecircle, label=\"\"]\n\n");
    out.write("  {rank=sink; -1 }\n");
    
    out.write("}\n");
    out.flush();
  }

  /**
   * Emit a single state in the <code>dot</code> language. 
   */
  private static void emitDotState(Writer out, String name, String shape,
      String color, String label) throws IOException {
    out.write("  " + name 
        + " [" 
        + (shape != null ? "shape=" + shape : "") + " "
        + (color != null ? "color=" + color : "") + " "
        + (label != null ? "label=\"" + label + "\"" : "label=\"\"") + " "
        + "]\n");
  }

  /**
   * Ensures an arc's label is indeed printable (dot uses US-ASCII). 
   */
  private static String printableLabel(int label) {
    if (label >= 0x20 && label <= 0x7d) {
      return Character.toString((char) label);
    } else {
      return "0x" + Integer.toHexString(label);
    }
  }

  /** Decodes the Unicode codepoints from the provided
   *  CharSequence and places them in the provided scratch
   *  IntsRef, which must not be null, returning it. */
  public static IntsRef toUTF32(CharSequence s, IntsRef scratch) {
    int charIdx = 0;
    int intIdx = 0;
    final int charLimit = s.length();
    while(charIdx < charLimit) {
      scratch.grow(intIdx+1);
      final int utf32 = Character.codePointAt(s, charIdx);
      scratch.ints[intIdx] = utf32;
      charIdx += Character.charCount(utf32);
      intIdx++;
    }
    scratch.length = intIdx;
    return scratch;
  }

  /** Decodes the Unicode codepoints from the provided
   *  char[] and places them in the provided scratch
   *  IntsRef, which must not be null, returning it. */
  public static IntsRef toUTF32(char[] s, int offset, int length, IntsRef scratch) {
    int charIdx = offset;
    int intIdx = 0;
    final int charLimit = offset + length;
    while(charIdx < charLimit) {
      scratch.grow(intIdx+1);
      final int utf32 = Character.codePointAt(s, charIdx);
      scratch.ints[intIdx] = utf32;
      charIdx += Character.charCount(utf32);
      intIdx++;
    }
    scratch.length = intIdx;
    return scratch;
  }

  /** Just takes unsigned byte values from the BytesRef and
   *  converts into an IntsRef. */
  public static IntsRef toIntsRef(BytesRef input, IntsRef scratch) {
    scratch.grow(input.length);
    for(int i=0;i<input.length;i++) {
      scratch.ints[i] = input.bytes[i+input.offset] & 0xFF;
    }
    scratch.length = input.length;
    return scratch;
  }
}
