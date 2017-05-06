package quanto.rewrite
import quanto.data._


case class MatchState(
                       m: Match,
                       finished: Boolean,
                       uNodes: Set[VName],
                       uWires: Set[VName],
                       pNodes: Set[VName],
                       psNodes: Set[VName],
                       tVerts: Set[VName],
                       angleMatcher: AngleExpressionMatcher) {

//  def copy(m: Match = this.m,
//           finished: Boolean = this.finished,
//           uNodes: Set[VName] = this.uNodes,
//           uWires: Set[VName] = this.uWires,
//           pNodes: Set[VName] = this.pNodes,
//           psNodes: Set[VName] = this.psNodes,
//           tVerts: Set[VName] = this.tVerts,
//           angleMatcher: AngleExpressionMatcher = this.angleMatcher) =
//    MatchState(m,finished,uNodes,uWires,pNodes,psNodes,
//      tVerts,angleMatcher)


  def matchPending(): Stream[MatchState] = {
    matchCircles().flatMap(_.matchMain())
  }

  // TODO: stub
  def matchCircles(): Stream[MatchState] =
    Stream(this)

  def matchMain(): Stream[MatchState] = {
    psNodes.headOption match {
      case Some(v) => continueMatchingFrom(v)
      case None => uNodes.headOption match {
        case Some(v) => matchAndScheduleNew(v)
        case None => Stream(this)
      }
    }
  }

  def continueMatchingFrom(np: VName): Stream[MatchState] =
    if (pVertexMayBeCompleted(np)) copy(psNodes = psNodes - np).matchNhd(np)
    else Stream()

  // TODO: stub
  def pVertexMayBeCompleted(v: VName) = true

  def matchNhd(np: VName): Stream[MatchState] = {
    val nt = m.vmap(np)
    m.pattern.adjacentEdges(np).find { e =>
      uWires.contains(m.pattern.edgeGetOtherVertex(e, np))
    } match {
      case Some(ep) =>
        m.target.adjacentEdges(nt).filter { e =>
            tVerts.contains(m.target.edgeGetOtherVertex(e,nt))
        }.foldRight(Stream[MatchState]()) { (et, stream) =>
          matchNewWire(np,ep,nt,et) match {
            case Some(ms1) => ms1.matchNhd(np) ++ stream
            case None => stream
          }
        }
      case None =>
        if (m.target.adjacentEdges(nt).forall(m.emap.codSet.contains))
          copy(pNodes = pNodes - nt).matchMain()
        else
          matchMain()
    }
  }


  def matchAndScheduleNew(np: VName): Stream[MatchState] = {
    val tNodes = tVerts.filter(!m.target.vdata(_).isWireVertex)
    tNodes.foldRight(Stream[MatchState]()) { (nt, stream) =>
      matchNewNode(np, nt) match {
        case Some(ms1) => ms1.matchMain() ++ stream
        case None => stream
      }
    }
  }

  /**
    * Match a new node vertex
    *
    * (ported from the ML function match_new_nv)
    *
    * @param np node vertex in the pattern
    * @param nt node vertex in the target
    * @return
    */
  def matchNewNode(np: VName, nt: VName): Option[MatchState] = {
    (m.pattern.vdata(np), m.target.vdata(nt)) match {
      case (pd: NodeV, td: NodeV) =>
        angleMatcher.addMatch(pd.angle, td.angle).map { angleMatcher1 =>
          copy(
            m = m.addVertex(np -> nt),
            uNodes = uNodes - np,
            pNodes = pNodes + np,
            psNodes = psNodes + np,
            tVerts = tVerts - nt
          )
        }
      case _ => None // should not happen. TODO: issue a warning?
    }
  }

  /**
   * Try to recursively add wire to matching, starting with the given head
   * vertex and edge. Return NONE on failure.
   *
   * (ported from the ML function tryadd_wire)
   *
   * @param vp already-matched vertex
   * @param ep unmatched edge incident to vp (other end must be in P, Uw or Un)
   * @param vt target of vp
   * @param et unmatched edge incident to vt
   */
  def matchNewWire(vp:VName, ep:EName, vt:VName, et:EName): Option[MatchState] = {
    val pdir = m.pattern.edata(ep).isDirected
    val tdir = m.target.edata(et).isDirected
    val pOutEdge = m.pattern.source(ep) == vp
    val tOutEdge = m.target.source(et) == vt

    // match directedness and, if the edge is directed, direction
    if ((pdir && tdir && pOutEdge == tOutEdge) || (!pdir && !tdir)) {
      val newVp = m.pattern.edgeGetOtherVertex(ep, vp)
      val newVt = m.target.edgeGetOtherVertex(et, vt)

      if (pNodes contains newVp) {
        if (m.vmap contains (newVp -> newVt))
          Some(copy(psNodes = psNodes + newVp, m = m.addEdge(ep -> et, newVp -> newVt)))
        else None
      } else if (tVerts contains newVt) {
        (m.pattern.vdata(newVp), m.target.vdata(newVt)) match {
          case (_: WireV, _: WireV) =>
            (m.pattern.wireVertexGetOtherEdge(newVp, ep), m.target.wireVertexGetOtherEdge(newVt, et)) match {
              case (Some(newEp), Some(newEt)) =>
                copy(
                  m = m.addEdge(ep -> et, newVp -> newVt),
                  tVerts = tVerts - newVt,
                  uNodes = uNodes - newVp
                ).matchNewWire(newVp, newEp, newVt, newEt)
              case (Some(_), None) => None
              case (None, _) =>
                Some(copy(
                  m = m.addEdge(ep -> et, newVp -> newVt),
                  tVerts = tVerts - newVt,
                  uNodes = uNodes - newVp
                ))
            }
          case (pdata: NodeV, tdata: NodeV) =>
            if (uNodes contains newVp) {
              angleMatcher.addMatch(pdata.angle, tdata.angle).map { angleMatcher1 =>
                copy(
                  m = m.addEdge(ep -> et, newVp -> newVt),
                  tVerts = tVerts - newVt,
                  uNodes = uNodes - newVp,
                  pNodes = pNodes + newVp,
                  psNodes = psNodes + newVp,
                  angleMatcher = angleMatcher1
                )
              }
            } else None
          case _ => None
        }
      } else None
    } else None
  }

}
