package hex;

import hex.KMeans.Initialization;

import java.util.ArrayList;
import java.util.Random;

import water.*;
import water.Job.ColumnsJob;
import water.api.*;
import water.fvec.Chunk;
import water.fvec.Vec;
import water.util.Utils;

/**
 * Scalable K-Means++ (KMeans||)<br>
 * http://theory.stanford.edu/~sergei/papers/vldb12-kmpar.pdf<br>
 * http://www.youtube.com/watch?v=cigXAxV3XcY
 */
public class KMeans2 extends ColumnsJob {
  static final int API_WEAVER = 1;
  static public DocGen.FieldDoc[] DOC_FIELDS;
  static final String DOC_GET = "k-means";

  @API(help = "Clusters initialization", filter = Default.class)
  public Initialization initialization = Initialization.None;

  @API(help = "Number of clusters", required = true, filter = Default.class, lmin = 2, lmax = 100000)
  public int k = 2;

  @API(help = "Maximum number of iterations before stopping", required = true, filter = Default.class, lmin = 1, lmax = 100000)
  public int max_iter = 100;

  @API(help = "Whether data should be normalized", filter = Default.class)
  public boolean normalize;

  @API(help = "Seed for the random number generator", filter = Default.class)
  public long seed = new Random().nextLong();

  public KMeans2() {
    description = "K-means";
  }

  @Override protected void exec() {
    String sourceArg = input("source");
    Key sourceKey = null;
    if( sourceArg != null )
      sourceKey = Key.make(sourceArg);
    String[] names = new String[cols.length];
    for( int i = 0; i < cols.length; i++ )
      names[i] = source._names[cols[i]];
    Vec[] vecs = selectVecs(source);
    // Fill-in response based on K
    String[] domain = new String[k];
    for( int i = 0; i < domain.length; i++ )
      domain[i] = "Cluster " + i;
    String[] namesResp = Utils.append(names, "response");
    String[][] domaiResp = (String[][]) Utils.append(source.domains(), (Object) domain);
    KMeans2Model model = new KMeans2Model(destination_key, sourceKey, namesResp, domaiResp);

    double[] subs = null, muls = null;
    if( normalize ) {
      subs = new double[vecs.length];
      muls = new double[vecs.length];

      for( int i = 0; i < vecs.length; i++ ) {
        subs[i] = (float) vecs[i].mean();
        double sigma = vecs[i].sigma();
        muls[i] = normalize(sigma) ? 1 / sigma : 1;
      }
    }

    // -1 to be different from all chunk indexes (C.f. Sampler)
    Random rand = Utils.getRNG(seed - 1);
    double[][] clusters;
    if( initialization == Initialization.None ) {
      // Initialize all clusters to random rows
      clusters = new double[k][vecs.length];
      for( int i = 0; i < clusters.length; i++ )
        randomRow(vecs, rand, clusters[i], subs, muls);
    } else {
      // Initialize first cluster to random row
      clusters = new double[1][];
      clusters[0] = new double[vecs.length];
      randomRow(vecs, rand, clusters[0], subs, muls);

      while( model.iterations < 5 ) {
        // Sum squares distances to clusters
        SumSqr sqr = new SumSqr();
        sqr._clusters = clusters;
        sqr._subs = subs;
        sqr._muls = muls;
        sqr.doAll(vecs);

        // Sample with probability inverse to square distance
        Sampler sampler = new Sampler();
        sampler._clusters = clusters;
        sampler._sqr = sqr._sqr;
        sampler._probability = k * 3; // Over-sampling
        sampler._seed = seed;
        sampler._subs = subs;
        sampler._muls = muls;
        sampler.doAll(vecs);
        clusters = Utils.append(clusters, sampler._sampled);

        if( cancelled() )
          return;
        model.clusters = normalize ? denormalize(clusters, vecs) : clusters;
        model.error = sqr._sqr;
        model.iterations++;
        UKV.put(destination_key, model);
      }

      clusters = recluster(clusters, k, rand, initialization);
    }

    for( ;; ) {
      Lloyds task = new Lloyds();
      task._clusters = clusters;
      task._subs = subs;
      task._muls = muls;
      task.doAll(vecs);
      model.clusters = normalize ? denormalize(task._means, vecs) : task._means;
      for( int clu = 0; clu < task._sigms.length; clu++ )
        for( int col = 0; col < task._sigms[clu].length; col++ )
          task._sigms[clu][col] = task._sigms[clu][col] / (task._rows[clu] - 1);
      model.variances = task._sigms;
      model.error = task._sqr;
      model.iterations++;
      UKV.put(destination_key, model);
      if( model.iterations >= max_iter )
        break;
      if( cancelled() )
        break;
    }
  }

  @Override protected Response redirect() {
    String n = KMeans2Progress.class.getSimpleName();
    return new Response(Response.Status.redirect, this, -1, -1, n, //
        "job_key", job_key, //
        "destination_key", destination_key);
  }

  public static class KMeans2Progress extends Progress2 {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @Override protected Response jobDone(Job job, Key dst) {
      return new Response(Response.Status.redirect, this, 0, 0, new KMeans2ModelView().href(), "model", destination_key);
    }
  }

  public static class KMeans2ModelView extends Request2 {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "KMeans2 Model", filter = Default.class)
    public KMeans2Model model;

    public static String link(String txt, Key model) {
      return "<a href='" + new KMeans2ModelView().href() + ".html?model=" + model + "'>" + txt + "</a>";
    }

    public static Response redirect(Request req, Key model) {
      return new Response(Response.Status.redirect, req, -1, -1, new KMeans2ModelView().href(), "model", model);
    }

    @Override protected Response serve() {
      return new Response(Response.Status.done, this, -1, -1, null);
    }

    @Override public boolean toHTML(StringBuilder sb) {
      if( model != null ) {
        DocGen.HTML.section(sb, "Error: " + model.error);
        sb.append("<span style='display: inline-block;'>");
        sb.append("<table class='table table-striped table-bordered'>");
        sb.append("<tr>");
        sb.append("<th>Clusters</th>");
        for( int i = 0; i < model.clusters[0].length; i++ )
          sb.append("<th>").append(model._names[i]).append("</th>");
        sb.append("</tr>");

        for( int r = 0; r < model.clusters.length; r++ ) {
          sb.append("<tr>");
          sb.append("<td>").append(r).append("</td>");
          for( int c = 0; c < model.clusters[r].length; c++ )
            sb.append("<td>").append(ElementBuilder.format(model.clusters[r][c])).append("</td>");
          sb.append("</tr>");
        }
        sb.append("</table></span>");
        return true;
      }
      return false;
    }
  }

  public static class KMeans2Model extends Model implements Progress {
    @API(help = "Cluster centers, always denormalized")
    public double[][] clusters;

    @API(help = "Sum of min square distances")
    public double error;

    @API(help = "Whether data was normalized")
    public boolean normalized;

    @API(help = "Maximum number of iterations before stopping")
    public int max_iter = 100;

    @API(help = "Iterations the algorithm ran")
    public int iterations;

    @API(help = "In-cluster variances")
    public double[][] variances;

    private transient double[] _subs, _muls; // Normalization
    private transient double[][] _normClust;

    public KMeans2Model(Key selfKey, Key dataKey, String names[], String domains[][]) {
      super(selfKey, dataKey, names, domains);
    }

    @Override public float progress() {
      return Math.min(1f, iterations / (float) max_iter);
    }

    @Override protected float[] score0(Chunk[] chunks, int rowInChunk, double[] tmp, float[] preds) {
      double[][] cs = clusters;
      if( normalized && _normClust == null ) {
        cs = _normClust = normalize(clusters, chunks);
        _subs = new double[chunks.length];
        _muls = new double[chunks.length];
        for( int i = 0; i < chunks.length; i++ ) {
          _subs[i] = (float) chunks[i]._vec.mean();
          double sigma = chunks[i]._vec.sigma();
          _muls[i] = normalize(sigma) ? 1 / sigma : 1;
        }
      }
      data(tmp, chunks, rowInChunk, _subs, _muls);
      preds[closest(cs, tmp, new ClusterDist())._cluster] = 1;
      return preds;
    }

    @Override protected float[] score0(double[] data, float[] preds) {
      throw new UnsupportedOperationException();
    }
  }

  public static class SumSqr extends MRTask2<SumSqr> {
    // IN
    double[] _subs, _muls; // Normalization
    double[][] _clusters;

    // OUT
    double _sqr;

    @Override public void map(Chunk[] cs) {
      double[] values = new double[cs.length];
      ClusterDist cd = new ClusterDist();
      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _subs, _muls);
        _sqr += minSqr(_clusters, values, cd);
      }
      _subs = _muls = null;
      _clusters = null;
    }

    @Override public void reduce(SumSqr other) {
      _sqr += other._sqr;
    }
  }

  public static class Sampler extends MRTask2<Sampler> {
    // IN
    double[][] _clusters;
    double _sqr;           // Min-square-error
    double _probability;   // Odds to select this point
    long _seed;
    double[] _subs, _muls; // Normalization

    // OUT
    double[][] _sampled;   // New clusters

    @Override public void map(Chunk[] cs) {
      double[] values = new double[cs.length];
      ArrayList<double[]> list = new ArrayList<double[]>();
      Random rand = Utils.getRNG(_seed + cs[0]._start);
      ClusterDist cd = new ClusterDist();

      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _subs, _muls);
        double sqr = minSqr(_clusters, values, cd);
        if( _probability * sqr > rand.nextDouble() * _sqr )
          list.add(values.clone());
      }

      _sampled = new double[list.size()][];
      list.toArray(_sampled);
      _clusters = null;
      _subs = _muls = null;
    }

    @Override public void reduce(Sampler other) {
      _sampled = Utils.append(_sampled, other._sampled);
    }
  }

  public static class Lloyds extends MRTask2<Lloyds> {
    // IN
    double[][] _clusters;
    double[] _subs, _muls;      // Normalization

    // OUT
    double[][] _means, _sigms;  // Means and sigma for each cluster
    long[] _rows;               // Rows per cluster
    double _sqr;                // Total sqr distance

    @Override public void map(Chunk[] cs) {
      _means = new double[_clusters.length][_clusters[0].length];
      _sigms = new double[_clusters.length][_clusters[0].length];
      _rows = new long[_clusters.length];

      // Find closest cluster for each row
      double[] values = new double[_clusters[0].length];
      ClusterDist cd = new ClusterDist();
      int[] clusters = new int[cs[0]._len];
      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _subs, _muls);
        closest(_clusters, values, cd);
        int clu = clusters[row] = cd._cluster;
        _sqr += cd._dist;
        if( clu == -1 )
          continue; // Ignore broken row

        // Add values and increment counter for chosen cluster
        for( int col = 0; col < values.length; col++ )
          _means[clu][col] += values[col];
        _rows[clu]++;
      }
      for( int clu = 0; clu < _means.length; clu++ )
        for( int col = 0; col < _means[clu].length; col++ )
          _means[clu][col] /= _rows[clu];
      // Second pass for in-cluster variances
      for( int row = 0; row < cs[0]._len; row++ ) {
        int clu = clusters[row];
        if( clu == -1 )
          continue;
        data(values, cs, row, _subs, _muls);
        for( int col = 0; col < values.length; col++ ) {
          double delta = values[col] - _means[clu][col];
          _sigms[clu][col] += delta * delta;
        }
      }
      _clusters = null;
      _subs = _muls = null;
    }

    @Override public void reduce(Lloyds mr) {
      for( int clu = 0; clu < _means.length; clu++ )
        Layer.Stats.reduce(_means[clu], _sigms[clu], _rows[clu], mr._means[clu], mr._sigms[clu], mr._rows[clu]);
      Utils.add(_rows, mr._rows);
      _sqr += mr._sqr;
    }
  }

  private static final class ClusterDist {
    int _cluster;
    double _dist;
  }

  private static ClusterDist closest(double[][] clusters, double[] point, ClusterDist cd) {
    return closest(clusters, point, cd, clusters.length);
  }

  private static double minSqr(double[][] clusters, double[] point, ClusterDist cd) {
    return closest(clusters, point, cd, clusters.length)._dist;
  }

  private static double minSqr(double[][] clusters, double[] point, ClusterDist cd, int count) {
    return closest(clusters, point, cd, count)._dist;
  }

  /** Return both nearest of N cluster/centroids, and the square-distance. */
  private static ClusterDist closest(double[][] clusters, double[] point, ClusterDist cd, int count) {
    int min = -1;
    double minSqr = Double.MAX_VALUE;
    for( int cluster = 0; cluster < count; cluster++ ) {
      double sqr = 0;           // Sum of dimensional distances
      int pts = point.length;   // Count of valid points
      for( int column = 0; column < clusters[cluster].length; column++ ) {
        double d = point[column];
        if( Double.isNaN(d) ) { // Bad data?
          pts--;                // Do not count
        } else {
          double delta = d - clusters[cluster][column];
          sqr += delta * delta;
        }
      }
      // Scale distance by ratio of valid dimensions to all dimensions - since
      // we did not add any error term for the missing point, the sum of errors
      // is small - ratio up "as if" the missing error term is equal to the
      // average of other error terms.  Same math another way:
      //   double avg_dist = sqr / pts; // average distance per feature/column/dimension
      //   sqr = sqr * point.length;    // Total dist is average*#dimensions
      if( pts < point.length )
        sqr *= point.length / pts;
      if( sqr < minSqr ) {
        min = cluster;
        minSqr = sqr;
      }
    }
    cd._cluster = min;          // Record nearest cluster
    cd._dist = minSqr;          // Record square-distance
    return cd;                  // Return for flow-coding
  }

  // KMeans++ re-clustering
  public static double[][] recluster(double[][] points, int k, Random rand, Initialization init) {
    double[][] res = new double[k][];
    res[0] = points[0];
    int count = 1;
    ClusterDist cd = new ClusterDist();
    switch( init ) {
      case PlusPlus: { // k-means++
        while( count < res.length ) {
          double sum = 0;
          for( int i = 0; i < points.length; i++ )
            sum += minSqr(res, points[i], cd, count);

          for( int i = 0; i < points.length; i++ ) {
            if( minSqr(res, points[i], cd, count) >= rand.nextDouble() * sum ) {
              res[count++] = points[i];
              break;
            }
          }
        }
        break;
      }
      case Furthest: { // Takes cluster further from any already chosen ones
        while( count < res.length ) {
          double max = 0;
          int index = 0;
          for( int i = 0; i < points.length; i++ ) {
            double sqr = minSqr(res, points[i], cd, count);
            if( sqr > max ) {
              max = sqr;
              index = i;
            }
          }
          res[count++] = points[index];
        }
        break;
      }
      default:
        throw new IllegalStateException();
    }
    return res;
  }

  private void randomRow(Vec[] vecs, Random rand, double[] cluster, double[] subs, double[] muls) {
    long row = Math.max(0, (long) (rand.nextDouble() * vecs[0].length()) - 1);
    data(cluster, vecs, row, subs, muls);
  }

  private static boolean normalize(double sigma) {
    // TODO unify handling of constant columns
    return sigma > 1e-6;
  }

  private static double[][] normalize(double[][] clusters, Chunk[] chks) {
    double[][] value = new double[clusters.length][clusters[0].length];
    for( int row = 0; row < value.length; row++ ) {
      for( int col = 0; col < clusters[row].length; col++ ) {
        double d = clusters[row][col];
        Vec vec = chks[col]._vec;
        d -= vec.mean();
        d /= normalize(vec.sigma()) ? vec.sigma() : 1;
        value[row][col] = d;
      }
    }
    return value;
  }

  private static double[][] denormalize(double[][] clusters, Vec[] vecs) {
    double[][] value = new double[clusters.length][clusters[0].length];
    for( int row = 0; row < value.length; row++ ) {
      for( int col = 0; col < clusters[row].length; col++ ) {
        double d = clusters[row][col];
        d *= vecs[col].sigma();
        d += vecs[col].mean();
        value[row][col] = d;
      }
    }
    return value;
  }

  /**
   * Return a row of normalized values. If missing, use the mean (which we know exists because we
   * filtered out columns with no mean).
   */
  private static void data(double[] values, Vec[] vecs, long row, double[] subs, double[] muls) {
    for( int i = 0; i < values.length; i++ ) {
      double d = vecs[i].at(row);
      if( subs != null ) {
        d -= subs[i];
        d *= muls[i];
      }
      values[i] = d;
    }
  }

  private static void data(double[] values, Chunk[] chks, int row, double[] subs, double[] muls) {
    for( int i = 0; i < values.length; i++ ) {
      double d = chks[i].at0(row);
      if( subs != null ) {
        d -= subs[i];
        d *= muls[i];
      }
      values[i] = d;
    }
  }
}
