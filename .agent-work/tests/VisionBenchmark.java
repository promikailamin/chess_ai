import com.chessai.engine.Position;
import com.chessai.vision.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class VisionBenchmark {
    public static void main(String[] args) throws Exception {
        boolean manual = args.length > 0 && args[0].equals("--manual");
        Path cases = Path.of(args.length > (manual?1:0) ? args[manual?1:0] : ".agent-work/tmp/eval_cases.tsv");
        List<String> lines = Files.readAllLines(cases);
        int total=0, exact=0, pieceTotal=0, pieceCorrect=0, detExact=0;
        Map<String,int[]> themes = new TreeMap<>();
        Map<Character,Map<Character,Integer>> confusion = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (String line: lines) {
            if (line.isBlank()) continue;
            String[] p=line.split("\t",5);
            String image=p[0], fen=p[1], theme=p[2], split=p[3], quad=p[4];
            Recognition r;
            try {
                if (manual) {
                    String q=quad.replaceAll("[\\[\\] ]","");
                    String[] n=q.split(",");
                    BoardQuad bq=new BoardQuad(Double.parseDouble(n[0]),Double.parseDouble(n[1]),Double.parseDouble(n[2]),Double.parseDouble(n[3]),Double.parseDouble(n[4]),Double.parseDouble(n[5]),Double.parseDouble(n[6]),Double.parseDouble(n[7]));
                    r=ScreenshotAnalyzer.analyze(new File(image),bq);
                } else r=ScreenshotAnalyzer.analyze(new File(image));
            } catch (Exception e) { failures.add(image+" CRASH "+e); total++; continue; }
            Position expected=Position.fromFen(fen);
            int pc=0;
            char[][] es=toChars(expected), as=r.squares;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++){
                char e=es[y][x], a=as[y][x];
                pieceTotal++;
                if(e==a)pc++;
                if(e!=a){
                    confusion.computeIfAbsent(e,k->new TreeMap<>()).merge(a,1,Integer::sum);
                }
            }
            pieceCorrect+=pc;
            boolean ok = pc==64;
            if(ok) exact++;
            int[] t=themes.computeIfAbsent(theme,k->new int[2]); t[0]++; if(ok)t[1]++;
            total++;
            if(!ok) failures.add(String.format("%s theme=%s split=%s pieces=%d/64 conf=%.2f\n  exp %s\n  got %s", image,theme,split,pc,r.confidence,expected.toFen(),r.position.toFen()));
        }
        System.out.printf("cases=%d exact=%d (%.1f%%) piece=%d/%d (%.2f%%)%n",total,exact,100.0*exact/total,pieceCorrect,pieceTotal,100.0*pieceCorrect/pieceTotal);
        for(var e:themes.entrySet()) System.out.printf("  %-20s %d/%d%n",e.getKey(),e.getValue()[1],e.getValue()[0]);
        System.out.println("confusion expected->got:");
        for(var e:confusion.entrySet()) System.out.println("  "+e.getKey()+" -> "+e.getValue());
        System.out.println("failures:");
        for(String f:failures) System.out.println(f);
    }
    static char[][] toChars(Position p){
        char[][] c=new char[8][8];
        for(int row=0;row<8;row++)for(int col=0;col<8;col++){
            int sq=(7-row)*8+col;
            int pc=p.pieceAt(sq);
            c[row][col]= pc==0?'.':com.chessai.engine.Piece.toChar(pc);
        } return c;
    }
}
