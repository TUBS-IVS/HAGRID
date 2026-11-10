import java.util.*;
final class DirectionalDP {
 final int[][] options;final double[][] costs;
 DirectionalDP(int[][] options,double[][] costs){this.options=options;this.costs=costs;}
 double value(int[] order){return new Route(order).value;}
 final class Route {
  final int[] order;final double[][] forward,backward;final double value;
  Route(int[] order){
   this.order=order.clone();int n=order.length;forward=new double[n+1][];backward=new double[n+1][];forward[0]=new double[]{0};backward[n]=new double[]{0};
   for(int i=1;i<=n;i++){int[] a=options[i==1?0:order[i-2]],b=options[order[i-1]];forward[i]=new double[b.length];Arrays.fill(forward[i],Double.POSITIVE_INFINITY);for(int x=0;x<a.length;x++)for(int y=0;y<b.length;y++)forward[i][y]=Math.min(forward[i][y],forward[i-1][x]+costs[a[x]][b[y]]);}
   for(int i=n-1;i>=0;i--){int[] a=options[order[i]],b=options[i==n-1?0:order[i+1]];backward[i]=new double[a.length];Arrays.fill(backward[i],Double.POSITIVE_INFINITY);for(int x=0;x<a.length;x++)for(int y=0;y<b.length;y++)backward[i][x]=Math.min(backward[i][x],costs[a[x]][b[y]]+backward[i+1][y]);}
   double v=Double.POSITIVE_INFINITY;int[] end=options[n==0?0:order[n-1]];for(int x=0;x<end.length;x++)v=Math.min(v,forward[n][x]+costs[end[x]][0]);value=v;
  }
  double insertionDelta(int job,int gap){
   int[] a=options[gap==0?0:order[gap-1]],b=options[job],c=options[gap==order.length?0:order[gap]];double v=Double.POSITIVE_INFINITY;
   for(int x=0;x<a.length;x++)for(int y:b)for(int z=0;z<c.length;z++)v=Math.min(v,forward[gap][x]+costs[a[x]][y]+costs[y][c[z]]+backward[gap][z]);
   return v-value;
  }
  int[] chosenStates(){
   int n=order.length;int[] result=new int[n+2];int previous=0;
   for(int i=0;i<n;i++){int[] opts=options[order[i]];double best=Double.POSITIVE_INFINITY;int winner=-1;for(int a=0;a<opts.length;a++){double candidate=costs[previous][opts[a]]+backward[i][a];if(candidate<best-1e-12){best=candidate;winner=opts[a];}}result[i+1]=winner;previous=winner;}
   return result;
  }
 }
}
