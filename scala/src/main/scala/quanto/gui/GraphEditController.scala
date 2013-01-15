package quanto.gui

import graphview._
import swing._
import swing.event._
import Key.Modifier
import quanto.data._

class GraphEditController(view: GraphView) {
  var mouseState: MouseState = SelectTool()

  // wire up the view's internal state
  def graph = view.graph
  def graph_=(g: Graph) { view.graph = g }
  def selectedVerts = view.selectedVerts
  def selectedVerts_=(s: Set[VName]) { view.selectedVerts = s }
  def selectedEdges = view.selectedEdges
  def selectedEdges_=(s: Set[EName]) { view.selectedEdges = s }
  def selectedBBoxes = view.selectedBBoxes
  def selectedBBoxes_=(s: Set[BBName]) { view.selectedBBoxes = s }

  private var _undoStack: UndoStack = new UndoStack
  view.listenTo(_undoStack)
  def undoStack = _undoStack
  def undoStack_=(s: UndoStack) {
    view.deafTo(_undoStack)
    _undoStack = s
    view.listenTo(_undoStack)
  }

  view.reactions += {
    case UndoPerformed(_) => view.repaint()
    case RedoPerformed(_) => view.repaint()
  }

  def shiftVertsNoRegister(vs: TraversableOnce[VName], p1: Point, p2: Point) {
    val (dx,dy) = (view.trans scaleFromScreen (p2.getX - p1.getX), view.trans scaleFromScreen (p2.getY - p1.getY))
    graph = vs.foldLeft(graph) { (g,v) =>
      view.invalidateVertex(v)
      graph.adjacentEdges(v) foreach (view.invalidateEdge(_))
      g.updateVData(v) { d => d.withCoord (d.coord._1 + dx, d.coord._2 - dy) }
    }
  }

  // shift vertices and register undo
  private def shiftVerts(vs: TraversableOnce[VName], p1: Point, p2: Point) {
    shiftVertsNoRegister(vs, p1, p2)
    undoStack.register("Move Vertices") { shiftVerts(vs, p2, p1) }
  }

  view.listenTo(view.mouse.clicks, view.mouse.moves)

  view.reactions += {

    case MousePressed(_, pt, modifiers, _, _) =>
      mouseState match {
        case SelectTool() =>
          val vertexHit = view.vertexDisplay find { _._2.pointHit(pt) } map { _._1 }
          val mouseDownOnSelectedVert = vertexHit exists (view.selectedVerts.contains(_))

          // clear the selection if the shift key isn't pressed and the vertex clicked isn't already selected
          if (!mouseDownOnSelectedVert &&
            (modifiers & Modifier.Shift) != Modifier.Shift)
          {
            selectedVerts = Set()
            selectedEdges = Set()
            selectedBBoxes = Set()
          }

          vertexHit match {
            case Some(v) =>
              selectedVerts += v // make sure v is selected, if it wasn't before
              mouseState = DragVertex(pt,pt)
            case None =>
              val box = SelectionBox(pt, pt)
              mouseState = box
              view.selectionBox = Some(box.rect)
          }

          view.repaint()

        case state => throw new InvalidMouseStateException("MousePressed", state)
      }

    case MouseDragged(_, pt, _) =>
      mouseState match {
        case SelectTool() => // do nothing
        case SelectionBox(start,_) =>
          val box = SelectionBox(start, pt)
          mouseState = box
          view.selectionBox = Some(box.rect)
          view.repaint()
        case DragVertex(start, prev) =>
          shiftVertsNoRegister(selectedVerts, prev, pt)
          view.repaint()
          mouseState = DragVertex(start, pt)
        case state => throw new InvalidMouseStateException("MouseMoved", state)
      }

    case MouseReleased(_, pt, modifiers, _, _) =>
      mouseState match {
        case SelectionBox(start,_) =>
          view.computeDisplayData()

          if (pt.getX == start.getX && pt.getY == start.getY) {
            var selectionUpdated = false
            view.vertexDisplay find (_._2.pointHit(pt)) foreach { x => selectionUpdated = true; selectedVerts += x._1 }

            if (!selectionUpdated)
              view.edgeDisplay find (_._2.pointHit(pt)) foreach { x => selectionUpdated = true; selectedEdges += x._1 }
            // TODO: bbox selection
          } else {
            // box selection only affects vertices
            val r = mouseState.asInstanceOf[SelectionBox].rect
            view.vertexDisplay filter (_._2.rectHit(r)) foreach { selectedVerts += _._1 }
          }

          mouseState = SelectTool()
          view.selectionBox = None
          view.repaint()

        case DragVertex(start, end) =>
          if (start.getX != end.getX || start.getY != end.getY) {
            // we don't call shiftVerts directly, because the vertices have already moved
            val verts = selectedVerts
            undoStack.register("Move Vertices") { shiftVerts(verts, end, start) }
          }

          mouseState = SelectTool()

        case state => throw new InvalidMouseStateException("MouseReleased", state)
      }

  }

}