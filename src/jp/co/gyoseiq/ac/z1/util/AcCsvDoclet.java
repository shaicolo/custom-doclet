package jp.co.gyoseiq.ac.z1.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.Doclet;
import com.sun.javadoc.ExecutableMemberDoc;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.MemberDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.ParamTag;
import com.sun.javadoc.Parameter;
import com.sun.javadoc.ProgramElementDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;

/**
 * プログラム仕様書に記述するドキュメント情報を<b>CSV<b>として出力するDocletです。
 * javadocツール実行時に、docletとして本クラスを指定することで出力できます。
 * jp.co.gyoseiq.ac.z1.util.AcCsvDoclet
 * @author shaicolo
 */
public class AcCsvDoclet extends Doclet {
	static Context cx = new Context();
	static DocComparator comparator = new DocComparator();

	public static boolean start(RootDoc rootDoc) {

		printValue("currentDir", new File("aaa").getAbsolutePath());
		for (ClassDoc c : rootDoc.classes()) {
			printClass(c);
		}

		return true;
	}

	/**
	 * コンテキスト
	 * @author shaicolo
	 */
	private static class Context implements Serializable {
		transient private final PrintWriter sysout = new PrintWriter(System.out);
		transient PrintWriter out;
		ClassDoc currentClass;
		Map<String, Set<String>> overloadMap;

		public Context() {
			out = sysout;
		}

		void startClass(ClassDoc c) {
			System.out.println("Start Class:" + c.qualifiedName());

			// 現在のクラス情報を設定
			currentClass = c;
			overloadMap = newOverloadMap(c);

			try {
				if (out != null && out != sysout) {
					out.close();
				}
				out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("out/" + c.qualifiedName() + ".txt"), "UTF-8"));
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
		void endClass() {
			if (out != null && out != sysout) {
				out.close();
			}
			out = sysout;
		}

		/**
		 * メソッド名をキーに、定義されているシグネチャーのセットを値としたマップを返します。
		 * メソッドはスーパークラスを遡り、コンストラクタは同クラスで定義されたものだけを取得します。
		 * オーバーロード判定用。
		 * @param c クラスドキュメント
		 * @return オーバーロードマップ
		 */
		private Map<String, Set<String>> newOverloadMap(ClassDoc c) {
			Map<String, Set<String>> ret =  new HashMap<String, Set<String>>() {
				@Override
				public Set<String> get(Object key) {
					if (!containsKey(key)) {
						put((String) key, new HashSet<String>());
					}
					return super.get(key);
				}
			};

			// コンストラクタのシグネチャを登録
			for (ConstructorDoc m : c.constructors(true)) {
				ret.get(m.name()).add(m.signature());
			}

			// メソッドのシグネチャを登録（再帰的に親を辿る
			while (c != null) {
				for (MethodDoc m : c.methods(true)) {
					ret.get(m.name()).add(m.signature());
				}
				c = c.superclass();
			}

			return ret;
		}
	}

	/**
	 * クラス情報を出力します。
	 * @param c クラスオブジェクト
	 */
	private static void printClass(ClassDoc c) {
		cx.startClass(c);

		List<List<String>> javadoc_method = new ArrayList<List<String>>();
		List<List<String>> javadoc_field = new ArrayList<List<String>>();

		separator("CLASS START " + c.name());
		printValue("name", c.name());
		printValue("package", c.containingPackage().name());
		for (ClassDoc itf : c.interfaces()) {
			printValue("interface", itf.qualifiedName());
		}
		printValue("parent", c.superclassType() == null ? "" : c.superclassType().qualifiedTypeName());
		printValue("abstract?", c.isAbstract());
		printValue("comment", deleteHtmlTag(c.commentText()));

		// ------------------------------------------------------------------------------
		separator("メソッド一覧");
		// ------------------------------------------------------------------------------
		{
			int mno = 0;
			separator2("コンストラクタ");
			ExecutableMemberDoc[] cons = c.constructors(true);
			Arrays.sort(cons, comparator);
			for (ExecutableMemberDoc em : cons) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}

			List<MethodDoc> meth = new ArrayList<MethodDoc>(Arrays.asList(c.methods(true)));
			meth.sort(comparator);

			// static
			List<MethodDoc> meth_static = filterOut(meth, new FilterProc<MethodDoc>() {
				@Override
				public boolean filter(MethodDoc value) {
					return value.isStatic();
				}
			});

			// abstract
			List<MethodDoc> meth_abstract = filterOut(meth, new FilterProc<MethodDoc>() {
				@Override
				public boolean filter(MethodDoc value) {
					return value.isAbstract();
				}
			});

			// final
			List<MethodDoc> meth_final = filterOut(meth, new FilterProc<MethodDoc>() {
				@Override
				public boolean filter(MethodDoc value) {
					return value.isFinal();
				}
			});

			separator2("メソッド");
			for (ExecutableMemberDoc em : meth) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}

			separator2("staticメソッド");
			for (ExecutableMemberDoc em : meth_static) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}

			separator2("インターフェースメソッド");
			for (ExecutableMemberDoc em : meth_abstract) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}

			separator2("継承禁止メソッド");
			for (ExecutableMemberDoc em : meth_final) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}
		}

		// ------------------------------------------------------------------------------
		separator("変数一覧");
		// ------------------------------------------------------------------------------
		{
			int fno = 0;
			List<FieldDoc> flds = new ArrayList<FieldDoc>(Arrays.asList(c.fields(true)));
			flds.sort(comparator);

			// final
			List<FieldDoc> flds_final = filterOut(flds, new FilterProc<FieldDoc>() {
				@Override
				public boolean filter(FieldDoc value) {
					return value.isFinal();
				}
			});

			separator2("定数");
			for (FieldDoc f : flds_final) {
				print変数一覧(++fno, f);
				javadoc_field.add(getJavadoc生成用コメント(fno, f));
			}

			separator2("変数");
			for (FieldDoc f : flds) {
				print変数一覧(++fno, f);
				javadoc_field.add(getJavadoc生成用コメント(fno, f));
			}
		}

		// ------------------------------------------------------------------------------
		separator("Javadoc生成用コメント");
		// ------------------------------------------------------------------------------
		{
			separator2("メソッド");
			for (List<String> buf : javadoc_method) {
				printRec(buf);
			}
			separator2("変数");
			for (List<String> buf : javadoc_field) {
				printRec(buf);
			}
		}

		separator("CLASS END " + c.name());
		cx.endClass();
	}

	/**
	 * 1件のメソッド定義について、メソッド一覧シートの記述内容をCSV形式で出力します。
	 * @param no 番号
	 * @param em メソッド定義
	 */
	private static void printメソッド一覧(int no, ExecutableMemberDoc em) {
		List<String> buf = new ArrayList<String>();

		Set<Tag> tagSet = new HashSet<Tag>();

		// ------------------------------------------------------------------------------
		// No.
		// ------------------------------------------------------------------------------
		buf.add(String.valueOf(no));

		// ------------------------------------------------------------------------------
		// 多態性
		// ------------------------------------------------------------------------------
		{
			List<String> b = new ArrayList<String>();

			// オーバーロード判定
			if (cx.overloadMap.get(em.name()).size() > 1) {
				b.add("オーバーロード");
			}

			// オーバーライド判定
			if (em.isMethod()) {
				MethodDoc m = (MethodDoc) em;
				if (m.overriddenMethod() != null) {
					b.add("オーバーライド");
				}
			}
			buf.add( join(b, "\n"));
		}

		// ------------------------------------------------------------------------------
		// 名前
		// ------------------------------------------------------------------------------
		buf.add(em.name());

		// ------------------------------------------------------------------------------
		// アクセス
		// ------------------------------------------------------------------------------
		buf.add(em.isPrivate() ? "private" : em.isProtected() ? "protected" : em.isPublic() ? "public" : em.isPackagePrivate() ? "" : "?");

		// ------------------------------------------------------------------------------
		// 戻り値の型
		// ------------------------------------------------------------------------------
		if (em.isMethod()) {
			StringBuffer b = new StringBuffer();
			b.append(((MethodDoc)em).returnType().qualifiedTypeName());
			b.append(" ");
			for (Tag t : em.tags("@return")) {
				b.append(t.text());
				tagSet.add(t);
			}
			buf.add(b.toString());
		} else {
			// コンストラクタは戻り値なし
			buf.add("-");
		}

		// ------------------------------------------------------------------------------
		// 引数
		// ------------------------------------------------------------------------------
		{
			Map<String, String> paramCommentMap = new HashMap<String, String>();
			List<String> args = new ArrayList<String>();

			// paramタグを先に取得
			for (ParamTag p : em.paramTags()) {
				printValue("param " + p.parameterName(), p.parameterComment());
				paramCommentMap.put(p.parameterName(), p.parameterComment());
				tagSet.add(p);
			}
			for (Parameter p : em.parameters()) {
				StringBuffer b = new StringBuffer(p.typeName());	// 型名
				b.append(" ");
				if (paramCommentMap.containsKey(p.name())) {
					// paramタグの説明を出力
					b.append(paramCommentMap.get(p.name()));
				} else {
					// paramタグがない場合は変数名を出力
					b.append(p.name());
				}
				args.add(b.toString());
			}

			if (args.size() == 0) {
				buf.add("なし");
			} else {
				buf.add(join(args, ",\n"));
			}
		}

		// ------------------------------------------------------------------------------
		// 機能
		// ------------------------------------------------------------------------------
		buf.add(deleteHtmlTag(em.commentText()));

		// ------------------------------------------------------------------------------
		// 備考・アノテーション
		// ------------------------------------------------------------------------------
		{
			// TODO: とりあえずアノテーションのみ
			List<String> b = new ArrayList<String>();
			for (AnnotationDesc an : em.annotations()) {
				// オーバーライドは処理済みなのでスキップ
				if (an.annotationType().name().equals("Override")) {
					continue;
				}
				StringBuffer sb = new StringBuffer(an.annotationType().name());
				if (an.elementValues().length > 0) {
					sb.append("(");
					boolean first = true;
					for (AnnotationDesc.ElementValuePair ae : an.elementValues()) {
						if (first) {
							first = false;
						} else {
							sb.append(", ");
						}
						sb.append(ae.element().name() + "=" + ae.value().value());
					}
					sb.append(")");
				}
			}

			buf.add(join(b, "\n"));
		}

		// ------------------------------------------------------------------------------

		// 出力
		printRec(buf);
	}

	/**
	 * 1件の変数定義について、変数一覧シートの記述内容をCSV形式で出力します。
	 * @param no 番号
	 * @param f 変数定義
	 */
	private static void print変数一覧(int no, FieldDoc f) {
		List<String> buf = new ArrayList<String>();

		Set<Tag> tagSet = new HashSet<Tag>();

		// ------------------------------------------------------------------------------
		// No.
		// ------------------------------------------------------------------------------
		buf.add(String.valueOf(no));

		// ------------------------------------------------------------------------------
		// 名前
		// ------------------------------------------------------------------------------
		buf.add(f.name());

		// ------------------------------------------------------------------------------
		// アクセス
		// ------------------------------------------------------------------------------
		buf.add(f.isPrivate() ? "private" : f.isProtected() ? "protected" : f.isPublic() ? "public" : f.isPackagePrivate() ? "" : "?");

		// ------------------------------------------------------------------------------
		// 型
		// ------------------------------------------------------------------------------
		{
			buf.add(f.type().qualifiedTypeName());
		}

		// ------------------------------------------------------------------------------
		// 値
		// ------------------------------------------------------------------------------
		{
			if (f.constantValueExpression() != null) {
				buf.add(f.constantValueExpression());
			} else {
				buf.add("");
			}
		}

		// ------------------------------------------------------------------------------
		// 機能
		// ------------------------------------------------------------------------------
		buf.add(deleteHtmlTag(f.commentText()));

		// ------------------------------------------------------------------------------
		// 備考・アノテーション
		// ------------------------------------------------------------------------------
		{
			// TODO: とりあえずアノテーションのみ
			List<String> b = new ArrayList<String>();
			for (AnnotationDesc an : f.annotations()) {
				StringBuffer sb = new StringBuffer(an.annotationType().name());
				if (an.elementValues().length > 0) {
					sb.append("(");
					boolean first = true;
					for (AnnotationDesc.ElementValuePair ae : an.elementValues()) {
						if (first) {
							first = false;
						} else {
							sb.append(", ");
						}
						sb.append(ae.element().name() + "=" + ae.value().value());
					}
					sb.append(")");
				}
			}

			buf.add(join(b, "\n"));
		}

		// ------------------------------------------------------------------------------

		// 出力
		printRec(buf);
	}


	/**
	 * 1件の変数定義について、変数一覧シートの記述内容をCSV形式で出力します。
	 * @param no 番号
	 * @param m 変数定義
	 */
	private static List<String> getJavadoc生成用コメント(int no, MemberDoc m) {
		List<String> buf = new ArrayList<String>();

		// ------------------------------------------------------------------------------
		// No.
		// ------------------------------------------------------------------------------
		buf.add(String.valueOf(no));

		// ------------------------------------------------------------------------------
		// 名前
		// ------------------------------------------------------------------------------
		buf.add(m.name());

		// ------------------------------------------------------------------------------
		// コメント
		// ------------------------------------------------------------------------------
		buf.add(m.getRawCommentText());

		// ------------------------------------------------------------------------------

		return buf;
	}

	private static void printMembers(String label, ProgramElementDoc[] ms) {
		for (ProgramElementDoc m : ms) {
			separator(label + " START " + m.name());
			Set<Tag> tagSet = new HashSet<Tag>();

			printValue("name", m.name());
			printValue("final?", m.isFinal());
			printValue("static?", m.isStatic());
			printValue("access", m.isPrivate() ? "private" : m.isProtected() ? "protected" : m.isPublic() ? "public" : m.isPackagePrivate() ? "package private" : "unknown");
			for (AnnotationDesc an : m.annotations()) {
				String buf = an.annotationType().name();
				if (an.elementValues().length > 0) {
					buf += "(";
					boolean first = true;
					for (AnnotationDesc.ElementValuePair ae : an.elementValues()) {
						if (first) {
							first = false;
						} else {
							buf += ", ";
						}
						buf += ae.element().name() + "=" + ae.value().value();
					}
					buf += ")";
				}
				printValue("annotation", buf);
			}
			if (m instanceof ExecutableMemberDoc) {
				ExecutableMemberDoc em = (ExecutableMemberDoc) m;
				printValue("native?", em.isNative());
				printValue("synchronized?", em.isSynchronized());

				Map<String, String> paramCommentMap = new HashMap<String, String>();
				for (ParamTag p : em.paramTags()) {
					printValue("param " + p.parameterName(), p.parameterComment());
					paramCommentMap.put(p.parameterName(), p.parameterComment());
					tagSet.add(p);
				}

				String args;
				if (em.parameters().length > 0) {
					args = "";
					boolean first = true;
					for (Parameter p : em.parameters()) {
						if (first) {
							first = false;
						} else {
							args += ",\n";
						}
						args += p.typeName() + " " + p.name();
						if (paramCommentMap.containsKey(p.name())) {
							args += " " + paramCommentMap.get(p.name());
						}
					}
				} else {
					args = "なし";
				}
				printValue("args", args);
			}
			if (m instanceof FieldDoc) {
				FieldDoc f = (FieldDoc) m;
				printValue("type", f.type().qualifiedTypeName());
				if (f.constantValueExpression() != null) {
					printValue("value", f.constantValueExpression());
				}
				printValue("transient?", f.isTransient());
				printValue("volatile?", f.isVolatile());
			}
			if (m instanceof ExecutableMemberDoc) {

			}

			printValue("comment", m.commentText());
			for (Tag t : m.tags()) {
				if (! tagSet.contains(t)) {
					printValue("tag(" + t.name() + ")", t.text());
				}
			}
			separator(label + " END");
		}
	}

	/**
	 * 区切り線を出力します。
	 * @param label ラベル
	 */
	private static void separator(String label) {
		if (label != null) {
			label = " " + label + " ";
		}
		cx.out.println("------------------------------------------------------------");
		cx.out.println("--- " + label);
		cx.out.println("------------------------------------------------------------");
	}

	/**
	 * 小区切り線を出力します。
	 * @param label ラベル
	 */
	private static void separator2(String label) {
		if (label != null) {
			label = " " + label + " ";
		}
		cx.out.println("■ " + label);
	}

	/**
	 * ラベル付きの値を出力します。
	 * @param label ラベル
	 * @param value 値
	 */
	private static void printValue(String label, Object value) {
		cx.out.println(label + ": " + value);
	}


	private static void printRec(List<String> rec) {
		cx.out.println(
			join(
				map(rec, new MapProc<String>() {
					public String proc(String value) {
						return "\"" + value.replaceAll("\"", "\"\"") + "\"";
					}
				}),
				","
			)
		);
	}

	private static String join(List<?> arr, String separator) {
		boolean first = true;
		StringBuffer buf = new StringBuffer();
		for (Object val : arr) {
			if (first) {
				first = false;
			} else {
				buf.append(separator);
			}
			buf.append(val);
		}
		return buf.toString();
	}

	private static <T>  List<T> map(List<T> arr, MapProc<T> proc) {
		List<T> ret = new ArrayList<T>();
		for (T value : arr) {
			ret.add(proc.proc(value));
		}
		return ret;
	}
	private static interface MapProc<T> {
		public T proc(T value);
	}

	private static <T>  List<T> filter(List<T> arr, FilterProc<T> filter) {
		List<T> ret = new ArrayList<T>();
		for (Iterator<T> i = arr.iterator(); i.hasNext(); ) {
			T value = i.next();
			if (filter.filter(value)) {
				ret.add(value);
			}
		}
		return ret;
	}
	private static <T>  List<T> filterOut(List<T> arr, FilterProc<T> filter) {
		List<T> ret = new ArrayList<T>();
		for (Iterator<T> i = arr.iterator(); i.hasNext(); ) {
			T value = i.next();
			if (filter.filter(value)) {
				ret.add(value);
				i.remove();
			}
		}
		return ret;
	}
	private static interface FilterProc<T> {
		public boolean filter(T value);
	}

	private static int countChar(String s, char target) {
		int count = 0;
		for (char c : s.toCharArray()) {
			if (c == target) {
				count++;
			}
		}
		return count;
	}

	private static String lpadNum(int value, int digits) {
		StringBuffer sb = new StringBuffer();
		sb.append(value);
		while (sb.length() < digits) {
			sb.insert(0, "0");
		}
		return sb.toString();
	}

	private static String deleteHtmlTag(String text) {
		return text.replaceAll("<.*?>", "");
	}

	private static class DocComparator implements Comparator<Doc> {

		@Override
		public int compare(Doc o1, Doc o2) {
			StringBuffer v1 = new StringBuffer(o1.name());
			StringBuffer v2 = new StringBuffer(o2.name());
			if (o1 instanceof ExecutableMemberDoc) {
				v1.append(lpadNum(countChar(((ExecutableMemberDoc) o1).flatSignature(), ','), 5));
				v1.append(((ExecutableMemberDoc) o1).flatSignature());
			}
			if (o2 instanceof ExecutableMemberDoc) {
				v2.append(lpadNum(countChar(((ExecutableMemberDoc) o2).flatSignature(), ','), 5));
				v2.append(((ExecutableMemberDoc) o2).flatSignature());
			}
			return v1.toString().compareTo(v2.toString());
		}

	}
}
