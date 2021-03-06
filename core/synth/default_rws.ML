signature SPIDER_REWRITES =
sig
  structure Theory : GRAPHICAL_THEORY
  val frob_rules : Theory.Graph.nvdata list -> Theory.Ruleset.T
  val frob_and_special_rules : Theory.Graph.nvdata list -> Theory.Ruleset.T
end

functor SpiderRewrites (
  structure Theory : GRAPHICAL_THEORY
) : SPIDER_REWRITES =
struct
  structure Theory = Theory
  structure Ruleset = Theory.Ruleset
  structure Rule = Theory.Rule
  structure Graph = Theory.Graph
  
  val edge = (Directed, Graph.default_edata)

  fun bx g = let
    val (vn,g) = g |> Graph.add_vertex Graph.WVert
    val (bn,g) = g |> Graph.add_bbox
  in (vn, #2 (Graph.add_to_bbox bn (V.NSet.single vn) g))
  end

  fun frob data = let
    val rhs = Graph.empty
    val (b1,rhs) = rhs |> bx
    val (b2,rhs) = rhs |> bx
    val (b3,rhs) = rhs |> bx
    val (b4,rhs) = rhs |> bx
    val (i1,rhs) = rhs |> Graph.add_vertex (Graph.NVert data)
    val rhs = rhs |> Graph.add_edge_anon edge b1 i1 |> Graph.add_edge_anon edge i1 b2
    val (i2,lhs) = rhs |> Graph.add_vertex (Graph.NVert data)
    val lhs = lhs |> Graph.add_edge_anon edge b3 i2 |> Graph.add_edge_anon edge i2 b4 |> Graph.add_edge_anon edge i1 i2
    val rhs = rhs |> Graph.add_edge_anon edge b3 i1 |> Graph.add_edge_anon edge i1 b4
  in Rule.mk (lhs,rhs)
  end
  
  fun special data = let
    val rhs = Graph.empty
    val (b1,rhs) = rhs |> bx
    val (b2,rhs) = rhs |> bx
    val (i1,rhs) = rhs |> Graph.add_vertex (Graph.NVert data)
    val rhs = rhs |> Graph.add_edge_anon edge b1 i1 |> Graph.add_edge_anon edge i1 b2
    val lhs = rhs |> Graph.add_edge_anon edge i1 i1
  in Rule.mk (lhs,rhs)
  end

  val t_redex = TagName.mk "redex"

  fun add_frob_and_special d rs =
  let
    val (fr, rs) = Ruleset.add_fresh_rule (frob d) rs
    val (sp, rs) = Ruleset.add_fresh_rule (special d) rs
  in rs |> Ruleset.tag_rule fr t_redex
        |> Ruleset.tag_rule sp t_redex
  end
  
  fun add_frob d rs =
  let
    val (fr, rs) = Ruleset.add_fresh_rule (frob d) rs
  in rs |> Ruleset.tag_rule fr t_redex
  end

  fun frob_rules ds = fold add_frob ds Ruleset.empty
  fun frob_and_special_rules ds = fold add_frob_and_special ds Ruleset.empty
end
