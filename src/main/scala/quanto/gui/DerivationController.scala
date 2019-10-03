package quanto.gui

import quanto.data._

import scala.swing._
import scala.swing.event.{ButtonClicked, Event, SelectionChanged}
import quanto.gui.histview._
import java.awt.Color

import quanto.gui.graphview.Highlight
import quanto.layout.{DerivationLayoutStrategy, DStepLayoutStrategy, RuleDStepLayoutStrategy}
import quanto.util.UserAlerts

sealed abstract class DeriveState extends HistNode { def step: Option[DSName] }
case class StepState(s: DSName) extends DeriveState {
  def color = new Color(180,255,180)
  def label = s.toString
  def step = Some(s)
}

case class HeadState(hOpt: Option[DSName]) extends DeriveState {
  def color = Color.WHITE
  def label = if (hOpt.isEmpty) "(root)" else "(head)"
  def step = hOpt
}

case class DeriveStateChanged(state: DeriveState) extends Event

class DerivationController(panel: DerivationPanel) extends Publisher {
  def derivation = panel.derivation

  // move to a named step in the derivation
  private var _state : DeriveState = HeadState(None)
  def state = _state
  def state_=(s: DeriveState) {
    if (_state != s) {
      val oldState = _state
      panel.document.undoStack.register("Change proof step") { state = oldState }
    }

    _state match {
      case HeadState(_) => panel.rewriteController.deafTo(panel.LhsView)
      case _ =>
    }

    _state = s
    panel.histView.selectedNode = Some(s)
    //panel.document.undoStack.clear() // weird things can happen if we keep the old undo stack around

    panel.PreviousButton.enabled = false
    panel.NextButton.enabled = false
    panel.RewindButton.enabled = false
    panel.FastForwardButton.enabled = false
    panel.NewHeadButton.enabled = false
    panel.DeleteStepButton.enabled = false
    panel.LayoutStepButton.enabled = false
    panel.InsertLayoutStepButton.enabled = false
    panel.ExportTheoremButton.enabled = false

    s match {
      case HeadState(headOpt) =>
        panel.Rhs.setHeadMode()
        panel.RhsLabel.text = "(head)"
        panel.RhsView.graphRef = panel.DummyRef

        headOpt match {
          case Some(head) => // at a named head
            panel.PreviousButton.enabled = true
            panel.RewindButton.enabled = true
            panel.DeleteStepButton.enabled = derivation.hasChildren(head)
            panel.ExportTheoremButton.enabled = true
            panel.LhsLabel.text = head.toString
            panel.LhsView.graphRef = panel.document.stepRef(head)
          case None => // at the root
            panel.NextButton.enabled = derivation.firstSteps.nonEmpty
            panel.FastForwardButton.enabled = derivation.heads.nonEmpty
            panel.LhsLabel.text = "(root)"
            panel.LhsView.graphRef = panel.document.rootRef
        }

        panel.LhsView.clearHighlights()
        panel.RhsView.clearHighlights()

        panel.rewriteController.listenTo(panel.LhsView)
        panel.rewriteController.restartSearch()


      case StepState(step) =>
        panel.NewHeadButton.enabled = !derivation.isHead(step)
        panel.DeleteStepButton.enabled = true
        panel.Rhs.setStepMode()
        panel.RhsLabel.text = step.toString
        panel.RhsView.graphRef = panel.document.stepRef(step)

        derivation.parentMap.get(step) match {
          case Some(parent) => // at a step with parent
            panel.RewindButton.enabled = true
            panel.PreviousButton.enabled = true
            panel.LhsLabel.text = parent.toString
            panel.LhsView.graphRef = panel.document.stepRef(parent)
          case None => // at a step with no parent (i.e. parent is root)
            panel.LhsLabel.text = "(root)"
            panel.LhsView.graphRef = panel.document.rootRef
        }



        val lhsV = derivation.steps(step).rule.lhs.verts //panel.LhsView.graphRef.graph.verts
        val rhsV = derivation.steps(step).rule.rhs.verts //panel.RhsView.graphRef.graph.verts

        // highlight where the rule was applied
        panel.LhsView.clearHighlights()
        panel.LhsView.addHighlight(new Highlight(Color.RED, lhsV))

        panel.RhsView.clearHighlights()
        panel.RhsView.addHighlight(new Highlight(Color.BLUE, rhsV))

        panel.NextButton.enabled = true
        panel.FastForwardButton.enabled = true
        panel.LayoutStepButton.enabled = true
        panel.InsertLayoutStepButton.enabled = true
    }

    publish(DeriveStateChanged(s))
  }

  def replaceDerivation(d: Derivation, desc: String) {
    val currentDerivation = panel.document.derivation
    panel.document.derivation = d

    panel.document.undoStack.register(desc) {
      replaceDerivation(currentDerivation, desc)
    }
  }

  def layoutDerivation(layoutProc : DerivationLayoutStrategy) {
    layoutProc.setDerivation(panel.document.derivation)
    replaceDerivation(layoutProc.layout().asDerivation(), "Layout Derivation")
    // force state refresh
    state = state
  }

  panel.derivationButtons.foreach { listenTo(_) }
  listenTo(panel.document, panel.histView.selection)

  reactions += {
    case DocumentChanged(_) =>
      panel.histView.treeData = derivation
      panel.histView.selectedNode = Some(state)
      panel.histView.ensureIndexIsVisible(panel.histView.selection.leadIndex)
    case DocumentReplaced(_) =>
      _state = HeadState(derivation.firstHead)
      state = state
      panel.histView.treeData = derivation
      panel.histView.selectedNode = Some(state)
//      println(derivation)
//      println(derivation.toSeq)
    case ButtonClicked(panel.RewindButton) =>
      state = HeadState(None)
    case ButtonClicked(panel.FastForwardButton) =>
      state match {
        case StepState(s) =>
          val ff = derivation.fastForward(s)
          state = HeadState(Some(ff))
        case HeadState(None) => // ff from root goes to the first head
          derivation.firstHead.map { h => state = HeadState(Some(h)) }
        case HeadState(Some(_)) => // can't ff from named head
      }
    case ButtonClicked(panel.PreviousButton) =>
      state match {
        case HeadState(sOpt) => sOpt.map { s => state = StepState(s) }
        case StepState(s) =>
          derivation.parentMap.get(s).map { p => state = StepState(p) }
      }
    case ButtonClicked(panel.NextButton) =>
      state match {
        case StepState(s) =>
          if (derivation.isHead(s)) state = HeadState(Some(s))
          else derivation.children(s).headOption.map { ch => state = StepState(ch) }
        case HeadState(None) =>
          derivation.firstSteps.headOption.map { ch => state = StepState(ch) }
        case HeadState(Some(_)) => // do nothing
      }
    case ButtonClicked(panel.NewHeadButton) =>
      state match {
        case StepState(s) =>
          panel.document.undoStack.start("Add proof head")
          replaceDerivation(derivation.addHead(s), "")
          state = HeadState(Some(s))
          panel.document.undoStack.commit()
        case _ => // do nothing
      }

    case ButtonClicked(panel.DeleteStepButton) =>
      state match {
        case HeadState(Some(s)) =>
          panel.document.undoStack.start("Delete proof head")
          state = StepState(s)
          replaceDerivation(derivation.deleteHead(s), "")
          panel.document.undoStack.commit()
        case StepState(s) =>
          if (Dialog.showConfirmation(
                title = "Confirm deletion",
                message = "This will delete " + derivation.allChildren(s).size +
                  " proof steps. Do you wish to continue?")
              == Dialog.Result.Yes)
          {
            val parentOpt = derivation.parentMap.get(s)

            panel.document.undoStack.start("Delete proof step")
            state = HeadState(parentOpt)
            replaceDerivation(derivation.deleteStep(s), "")
            if(parentOpt.nonEmpty && !derivation.isHead(parentOpt.get)){
              val p = parentOpt.get
              replaceDerivation(derivation.addHead(p), "")
              state = HeadState(Some(p))
            }

            panel.document.undoStack.commit()
          }
        case _ => // do nothing on root
      }

    case ButtonClicked(panel.LayoutStepButton) => {
      state match {
        case StepState(s) => {
          val parentDSO = derivation.parentMap.get(s)

          panel.document.undoStack.start("Lay out proof step")

          val layoutStrategy : DStepLayoutStrategy = new RuleDStepLayoutStrategy;
          if (parentDSO.isEmpty) {
            layoutStrategy.setStep(derivation.steps(s), derivation.root)
          }
          else {
            layoutStrategy.setStep(derivation.steps(s), derivation.steps(parentDSO.get).graph)
          }
          val newDerivation = derivation.updateGraphInStep(s, layoutStrategy.layoutOutput().asGraph())
          if (parentDSO.isEmpty) {
            replaceDerivation(newDerivation.copy(root=layoutStrategy.layoutSource().asGraph()), "")
          }
          else {
            replaceDerivation(newDerivation.updateGraphInStep(parentDSO.get, layoutStrategy.layoutSource().asGraph()), "")
          }
          state = StepState(s) // Refresh the display.

          panel.document.undoStack.commit()
        }
        case _ => // do nothing on root and heads, which are not DSteps
      }
    }

    case ButtonClicked(panel.InsertLayoutStepButton) => {
      state match {
        case StepState(s) => {
          val parentDSO = derivation.parentMap.get(s)

          panel.document.undoStack.start("Lay out and add proof step")

          val layoutStrategy : DStepLayoutStrategy = new RuleDStepLayoutStrategy;
          if (parentDSO.isEmpty) {
            layoutStrategy.setStep(derivation.steps(s), derivation.root)
          }
          else {
            layoutStrategy.setStep(derivation.steps(s), derivation.steps(parentDSO.get).graph)
          }
          val newStep = new DStep((new Names.NameSet(derivation.steps.keySet)).freshWithSuggestion(new DSName("/no-op/")), null, new Rule(new Graph, new Graph), layoutStrategy.layoutSource().asGraph()) // Slashes in the name make it an illegal file name, to avoid confusion with actual rules with associated files.
          var newDerivation = derivation.updateGraphInStep(s, layoutStrategy.layoutOutput().asGraph())
          newDerivation = newDerivation.copy(steps = newDerivation.steps + (newStep.name -> newStep), parent = newDerivation.parentMap + (s -> newStep.name))
          if (parentDSO.isEmpty) {
            replaceDerivation(newDerivation.copy(parent = newDerivation.parentMap - newStep.name), "")
          }
          else {
            replaceDerivation(newDerivation.copy(parent = newDerivation.parentMap + (newStep.name -> parentDSO.get)), "")
          }
          state = StepState(s) // Refresh the display.

          panel.document.undoStack.commit()
        }
        case _ => // do nothing on root and heads, which are not DSteps
      }
    }

    case ButtonClicked(panel.ExportTheoremButton) =>
      if(!panel.document.unsavedChanges && panel.document.file.nonEmpty) {
        val f = panel.document.file.get
        val rf = panel.project.rootFolder
        var dname = panel.project.relativePath(f)
        dname = if (dname.endsWith(".qderive")) dname.substring(0, dname.length - 8) else dname

        state.step.foreach { s =>
          val ruleDoc = new RuleDocument(panel, panel.theory)
          val newRule = new Rule(panel.document.root, derivation.steps(s).graph, Some(dname))
          val page = new RuleDocumentPage(panel.theory)
          page.document.asInstanceOf[RuleDocument].rule = newRule
          QuantoDerive.addAndFocusPage(page)
        }

      } else {
          UserAlerts.errorBox("You must first save this derivation before exporting a theorem")
      }

    case SelectionChanged(_) =>
      if (panel.histView.selectedNode != Some(state)) {
        panel.histView.selectedNode.map { st => state = st }
        panel.histView.ensureIndexIsVisible(panel.histView.selectedIndex())
      }
  }
}
