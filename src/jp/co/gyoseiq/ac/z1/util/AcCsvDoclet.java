package jp.co.gyoseiq.ac.z1.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.Doclet;
import com.sun.javadoc.ExecutableMemberDoc;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.LanguageVersion;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.ParamTag;
import com.sun.javadoc.Parameter;
import com.sun.javadoc.ProgramElementDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;
import com.sun.javadoc.TypeVariable;

// TODO: 一部のHTMLタグを認識（<li>→箇条書き表示　など）
// TODO: アノテーションの引数が変な風に出る
// TODO: 不要なアノテーション（SupressWarningなど）を消す
// TODO: 型パラメータの出力（※必要？）
// TODO: ビヘイビア一覧（※多分ムリ）

/**
 * プログラム仕様書に記述するドキュメント情報を<b>CSV</b>として出力するDocletです。
 * javadocツール実行時に、docletとして本クラスを指定することで出力できます。
 * 【使用方法】
 * javadocコマンドでdocletに指定してください。
 * 【対象外・制限事項】
 * テステス
 * @author shaicolo
 */
public class AcCsvDoclet extends Doclet {
	static Context cx;
	static final Pattern RX_LINE = Pattern.compile("[^\n]*\n");
	static final Pattern RX_FIELDINITIALIZER = Pattern.compile("^[ \\t\\r\\n]*=[ \\t\\r\\n]*([^;]*);");
	static final Pattern RX_SIYOU = Pattern.compile("(.*)【使用方法】*(.*)", Pattern.DOTALL);
	static final Pattern RX_SEIGEN = Pattern.compile("(.*)【対象外・制限事項】*(.*)", Pattern.DOTALL);
	static DocComparator comparator = new DocComparator();

	public static boolean start(RootDoc rootDoc) {

		cx = new Context(rootDoc.options());

		// 改行コードを\nにセット
		System.setProperty("line.separator", "\n");

		printValue("currentDir", new File("aaa").getAbsolutePath());
		Map<String,Exception> resultMap = new HashMap<String, Exception>();
		for (ClassDoc c : rootDoc.classes()) {
			try {
				printClass(c);
				resultMap.put(c.name(), null);
			} catch (Exception ex) {
				resultMap.put(c.name(), ex);
				ex.printStackTrace();
			}
		}
		
		System.out.println("=====================================================");
		System.out.flush();
		
		boolean result = true;
		for (Map.Entry<String, Exception> i : resultMap.entrySet()) {
			String cls = i.getKey();
			Exception ex = i.getValue();
			if (ex == null) {
				System.out.println(cls + "：正常終了");
			} else {
				System.err.println(cls + "：エラー：" + ex);
				result = false;
			}
		}

		System.out.println("=====================================================");

		return result;
	}

	// Java1.5記法をサポート
	public static LanguageVersion languageVersion() {
		return LanguageVersion.JAVA_1_5;
	}

	// サポートする追加オプションの定義
	public static int optionLength(String option) {

		// 出力フォルダー指定
		if (option.equals("-destdir")) {
			return 2;
		}

		// 表示リビジョン番号指定
		if (option.equals("-revision")) {
			return 2;
		}

		return 0;
	}

	/**
	 * コンテキスト
	 * @author shaicolo
	 */
	private static class Context implements Serializable {
		transient PrintStream out;
		File destdir;
		ClassDoc currentClass;
		String currentSourceBuf;
		List<Integer> sourceLinePosList;
		Map<String, String[]> params = new HashMap<String, String[]>();
		Map<String, Set<String>> overloadMap;

		public Context(String[][] args) {
			out = System.out;

			// デフォルトパラメータ
			params.put("-destdir", new String[]{"work/progSpec"});
			params.put("-revision", new String[]{"リビジョン"});

			// 引数パラメータ
			for (String[] arg : args) {
				//System.out.println("params: " + Arrays.toString(arg));
				if (optionLength(arg[0]) > 1) {
					params.put(arg[0], Arrays.copyOfRange(arg, 1, arg.length));
				} else if (optionLength(arg[0]) == 1) {
					params.put(arg[0], new String[0]);
				}
			}

			// 出力フォルダー作成
			destdir = new File(params.get("-destdir")[0]);
		}

		void startClass(ClassDoc c) {
			System.out.println("Class:" + c.qualifiedName());

			// 現在のクラス情報を設定
			ClassDoc prevClass = currentClass;
			currentClass = c;

			// ソース読込（変数初期値取得用）
			if (prevClass == null || !currentClass.position().file().equals(prevClass.position().file())) {
				try {
					char[] cb = new char[1048576];
					BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(c.position().file()), "UTF-8"));
					int off = 0;
					int len = 0;
					while (off < cb.length && len >= 0) {
						len = br.read(cb, off, cb.length - off);
						if (len > 0) {
							off += len;
						}
					}
					if (len > 0) {
						System.err.println("WARNING: " + c.position().file().getName() + " の文字数が " + cb.length + " を超えます。");
					}
					currentSourceBuf = tabConvert(new String(cb, 0, off), 8);

					Matcher mcr = RX_LINE.matcher(currentSourceBuf);
					sourceLinePosList = new ArrayList<Integer>();
					while (mcr.find()) {
						sourceLinePosList.add(mcr.start());
					}
				} catch (IOException ex) {
					ex.printStackTrace();
					throw new RuntimeException(ex);
				}
			}

			overloadMap = newOverloadMap(c);

			try {
				if (out != null && out != System.out) {
					out.close();
				}
				File outdir = new File(destdir, c.position().file().getName().replaceAll("\\.java$", ""));
				if (!outdir.exists()) {
					outdir.mkdirs();
				}
				File outf = new File(outdir, c.name() + ".csv");
				System.out.println(" -> " + outf.getAbsolutePath());
				out = new PrintStream(new FileOutputStream(outf), false, "UTF-8");

				// BOM
				out.write(0xef);
				out.write(0xbb);
				out.write(0xbf);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
		void endClass() {
			if (out != null && out != System.out) {
				out.close();
			}
			out = System.out;
		}

		/**
		 * メソッド名をキーに、定義されているシグネチャーのセットを値としたマップを返します。
		 * メソッドはスーパークラスを遡り、コンストラクタは同クラスで定義されたものだけを取得します。
		 * オーバーロード判定用。
		 * @param c クラスドキュメント
		 * 	＜改行以降が削除されるのテスト用文字列＞
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
		printValue("パッケージ", c.containingPackage().name());
		printValue("クラス", genericClassName(c));
		//printValue("containingClass", c.containingClass() == null ? "－" : c.containingClass().name());
		//printValue("pos.file", c.position().file().getName());
		//printValue("pos.line", c.position().line());
		printValue("継承クラス", c.superclassType() == null ? "－" : c.superclassType().qualifiedTypeName());
		printValue("インターフェース", join(
			map(Arrays.asList((Object[]) c.interfaces()), new MapProc<Object>() {
				public Object proc(Object value) {
					ClassDoc itf = (ClassDoc) value;
					return itf.qualifiedName();
				}
			}), ", "
		));
		//printValue("abstract?", c.isAbstract());
		cx.out.println();
		{
			String strGaiyou = deleteHtmlTag(description(c.commentText()));
			String strSiyou = null;
			String strSeigen = null;
			for (Matcher mcr = RX_SEIGEN.matcher(strGaiyou); mcr.matches();) {
				strSeigen = mcr.group(2);
				strGaiyou = mcr.group(1);
				break;
			}
			for (Matcher mcr = RX_SIYOU.matcher(strGaiyou); mcr.matches();) {
				strSiyou = mcr.group(2);
				strGaiyou = mcr.group(1);
				break;
			}
			printValue("【概要】", strGaiyou);
			printValue("【使用方法】", strSiyou);
			printValue("【対象外・制限事項】", strSeigen);
		}

		// ------------------------------------------------------------------------------
		separator("メソッド一覧");
		// ------------------------------------------------------------------------------
		{
			// 見出し出力
			printメソッド一覧(0, null);

			int mno = 0;

			// ---------------------------------------
			// コンストラクタ
			// ---------------------------------------
			separator2("コンストラクタ");
			ExecutableMemberDoc[] cons = c.constructors(true);

			// 名前順にソート
			Arrays.sort(cons, comparator);

			// 出力
			for (ExecutableMemberDoc em : cons) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}

			// ---------------------------------------
			// メソッド
			// ---------------------------------------
			List<MethodDoc> meth = new ArrayList<MethodDoc>(Arrays.asList(c.methods(true)));

			// 名前順にソート
			Collections.sort(meth, comparator);

			// クラスメソッド（staticメソッド）仕分け
			List<MethodDoc> meth_static = filterOut(meth, new FilterProc<MethodDoc>() {
				@Override
				public boolean filter(MethodDoc value) {
					return value.isStatic();
				}
			});

			// 抽象メソッド（abstractメソッド）仕分け
			List<MethodDoc> meth_abstract = filterOut(meth, new FilterProc<MethodDoc>() {
				@Override
				public boolean filter(MethodDoc value) {
					return value.isAbstract();
				}
			});

			// 継承禁止メソッド（finalメソッド）仕分け
			List<MethodDoc> meth_final = filterOut(meth, new FilterProc<MethodDoc>() {
				@Override
				public boolean filter(MethodDoc value) {
					return value.isFinal();
				}
			});

			// ※ 残ったメソッドがインスタンスメソッド
			// ※ static, abstract, final はそれぞれ両立しないので、上記処理は順不同で問題無い

			// 出力（mnoはコンストラクタから継続）
			separator2("インスタンスメソッド");
			for (ExecutableMemberDoc em : meth) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}
			if (meth.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}

			separator2("抽象メソッド");
			for (ExecutableMemberDoc em : meth_abstract) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}
			if (meth_abstract.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}

			separator2("クラスメソッド");
			for (ExecutableMemberDoc em : meth_static) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}
			if (meth_static.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}

			separator2("継承禁止メソッド");
			for (ExecutableMemberDoc em : meth_final) {
				printメソッド一覧(++mno, em);
				javadoc_method.add(getJavadoc生成用コメント(mno, em));
			}
			if (meth_final.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}
		}

		// ------------------------------------------------------------------------------
		separator("変数一覧");
		// ------------------------------------------------------------------------------
		{
			// 見出し出力
			print変数一覧(0, null);

			// 名前順にソート
			ArrayList<FieldDoc> flds = new ArrayList<FieldDoc>(Arrays.asList(c.fields(true)));
			Collections.sort(flds, comparator);

			// 定数（finalフィールド）仕分け
			List<FieldDoc> flds_final = filterOut(flds, new FilterProc<FieldDoc>() {
				@Override
				public boolean filter(FieldDoc value) {
					return value.isFinal();
				}
			});
			// 直下に定義された列挙型の定数を追加して名前順ソート
			for (ClassDoc ic : c.innerClasses()) {
				if (ic.isEnum()) {
					flds_final.addAll(Arrays.asList(ic.enumConstants()));
				}
			}
			Collections.sort(flds_final, comparator);

			// クラス変数（staticフィールド）仕分け
			List<FieldDoc> flds_static = filterOut(flds, new FilterProc<FieldDoc>() {
				@Override
				public boolean filter(FieldDoc value) {
					return value.isStatic();
				}
			});

			// ※残ったフィールドがインスタンス変数

			// 出力
			int fno = 0;

			separator2("定数");
			for (FieldDoc f : flds_final) {
				print変数一覧(++fno, f);
				javadoc_field.add(getJavadoc生成用コメント(fno, f));
			}
			if (flds_final.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}

			separator2("クラス変数");
			for (FieldDoc f : flds_static) {
				print変数一覧(++fno, f);
				javadoc_field.add(getJavadoc生成用コメント(fno, f));
			}
			if (flds_static.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}

			separator2("インスタンス変数");
			for (FieldDoc f : flds) {
				print変数一覧(++fno, f);
				javadoc_field.add(getJavadoc生成用コメント(fno, f));
			}
			if (flds.size() == 0) {
				printRec(Arrays.asList(new String[]{"－","－","－","－","－"}));
			}
		}

		// ------------------------------------------------------------------------------
		separator("Javadoc生成用コメント");
		// ------------------------------------------------------------------------------
		{
			// 見出し出力
			printRec(getJavadoc生成用コメント(0, null));

			separator2("クラス");
			printRec(getJavadoc生成用コメント(1, cx.currentClass));

			cx.out.println();
			separator2("メソッド");
			for (List<String> buf : javadoc_method) {
				printRec(buf);
			}

			cx.out.println();
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
	 * @param m メソッド定義（nullの場合は見出しを出力）
	 */
	private static void printメソッド一覧(int no, ExecutableMemberDoc m) {
		List<String> buf = new ArrayList<String>();

		Set<Tag> tagSet = new HashSet<Tag>();	// 処理済みタグ
		Set<String> annoSet = new HashSet<String>(); // 処理済みアノテーション
		StringBuffer additionalDescription = new StringBuffer();	// 機能部分への追加コメント（引数・戻り値の詳細）
		List<String> bikou = new ArrayList<String>();	// 備考・アノテーション

		// ------------------------------------------------------------------------------
		// No.
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("No.");
		} else {
			buf.add(String.valueOf(no));
		}

		// ------------------------------------------------------------------------------
		// 多態性
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("多態性");
		} else {
			List<String> b = new ArrayList<String>();

			// オーバーロード判定
			if (cx.overloadMap.get(m.name()).size() > 1) {
				b.add("オーバーロード");
			}

			// オーバーライド判定
			boolean bOverride = false;
			MethodDoc mOverride = null;
			if (m.isMethod()) {
				mOverride = overrides((MethodDoc)m);

				// Overrideアノテーションも確認
				for (AnnotationDesc an : m.annotations()) {
					if (an.annotationType().name().equals("Override")) {
						bOverride = true;
						annoSet.add(an.toString());
					}
				}
			}

			if (bOverride || mOverride != null) {
				b.add("オーバーライド");

				// オーバーライドするメソッドが取得できていれば備考を追加
				if (mOverride != null) {
					bikou.add("オーバーライド: " + mOverride.containingClass().name());
				} else {
					System.err.println("WARNING: オーバーライド先が取得できませんでした: " + m);
				}
			}
			buf.add( join(b, "\n"));
		}

		// ------------------------------------------------------------------------------
		// メソッド名
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("メソッド名");
		} else {
			buf.add(m.name());
		}

		// ------------------------------------------------------------------------------
		// アクセス
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("アクセス");
		} else {
			buf.add(m.isPrivate() ? "private" : m.isProtected() ? "protected" : m.isPublic() ? "public" : m.isPackagePrivate() ? "" : "?");
		}

		// ------------------------------------------------------------------------------
		// 戻り値
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("戻り値");
		} else {
			if (m.isMethod()) {
				StringBuffer b = new StringBuffer();
				b.append(typeName(((MethodDoc)m).returnType()));
				b.append(" ");
				for (Tag t : m.tags("@return")) {

					// 戻り値コメントを取得
					String retComment = deleteHtmlTag(t.text());

					//（最初の行のみ）
					String retCommentFirst = retComment.replaceAll("\\n.*", "");

					// 戻り値欄の説明には先頭行のみを追加し、複数行ある場合は機能覧に全体の説明を追加。
					b.append(retCommentFirst);
					if (!retComment.equals(retCommentFirst)) {
						additionalDescription.append("\n\n");
						additionalDescription.append("※" + retComment);
					}
					tagSet.add(t);
				}
				buf.add(b.toString());
			} else {
				// コンストラクタは戻り値なし
				buf.add("-");
			}
		}

		// ------------------------------------------------------------------------------
		// 引数
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("引　数");
		} else {
			Map<String, String> paramCommentMap = new HashMap<String, String>();
			List<String> args = new ArrayList<String>();

			// paramタグを先に取得
			for (ParamTag p : m.paramTags()) {

				// paramコメントを取得
				String paramComment = deleteHtmlTag(p.parameterComment());

				//（最初の行のみ）
				String paramCommentFirst = paramComment.replaceAll("\\n.*", "");

				// 引数欄の説明には先頭行のみを追加し、複数行ある場合は機能覧に全体の説明を追加。
				paramCommentMap.put(p.parameterName(), paramCommentFirst);
				if (!paramComment.equals(paramCommentFirst)) {
					additionalDescription.append("\n\n");
					additionalDescription.append("※" + paramComment);
				}
				tagSet.add(p);
			}
			for (Parameter p : m.parameters()) {
				StringBuffer b = new StringBuffer(typeName(p.type()));	// 型名
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
		if (m == null) {
			buf.add("機　能");
		} else {
			buf.add(deleteHtmlTag(description(m.commentText())) + additionalDescription.toString());
		}

		// ------------------------------------------------------------------------------
		// 備考・アノテーション・AF呼出
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("備考・アノテーション・AF呼出");
		} else {
			// TODO: とりあえずアノテーションのみ＋オーバーライド対象
			bikou.addAll(annotation(m, annoSet));
			buf.add(join(bikou, "\n"));
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
	private static void print変数一覧(int no, FieldDoc m) {
		List<String> buf = new ArrayList<String>();
		Set<String> annoSet = new HashSet<String>(); // 処理済みアノテーション

		// ------------------------------------------------------------------------------
		// No.
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("No.");
		} else {
			buf.add(String.valueOf(no));
		}

		// ------------------------------------------------------------------------------
		// 変数名
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("変数");
		} else {
			buf.add(m.name());
		}

		// ------------------------------------------------------------------------------
		// 型
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("型");
		} else {
			buf.add(typeName(m.type()));
		}

		// ------------------------------------------------------------------------------
		// アクセス
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("アクセス");
		} else {
			buf.add(m.isPrivate() ? "private" : m.isProtected() ? "protected" : m.isPublic() ? "public" : m.isPackagePrivate() ? "" : "?");
		}

		// ------------------------------------------------------------------------------
		// 初期値
		// ------------------------------------------------------------------------------
		// TODO: Doclet APIでは定数の場合にしか初期値を抽出できない模様
		if (m == null) {
			buf.add("初期値");
		} else {
			if (m.constantValueExpression() != null) {
				buf.add(m.constantValueExpression());
			} else {
				// 定数以外の場合は、ソースから記述を探す
				int lno = m.position().line() - 1;
				int cpos = m.position().column() - 1 + m.name().length();
				int spos = cx.sourceLinePosList.get(lno) + cpos;

				Matcher mcr = RX_FIELDINITIALIZER.matcher(cx.currentSourceBuf.substring(spos));
				if (mcr.find()) {
					buf.add(mcr.group(1).replaceAll("[ \\t\\r\\n]+$", ""));
				} else {
					buf.add("");
				}
			}
		}

		// ------------------------------------------------------------------------------
		// 機能
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("機　能");
		} else {
			buf.add(deleteHtmlTag(description(m.commentText())));
		}

		// ------------------------------------------------------------------------------
		// 備考・アノテーション
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("備考・アノテーション");
		} else {
			// TODO: とりあえずアノテーションのみ
			buf.add(join(annotation(m, annoSet),"\n"));
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
	private static List<String> getJavadoc生成用コメント(int no, Doc m) {
		List<String> buf = new ArrayList<String>();

		// ------------------------------------------------------------------------------
		// No.
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("No.");
		} else {
			buf.add(String.valueOf(no));
		}

		// ------------------------------------------------------------------------------
		// 名前
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("名前");
		} else {
			buf.add(m.name());
		}

		// ------------------------------------------------------------------------------
		// コメント
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("コメント");
		} else {
			buf.add(fullComment(m.getRawCommentText()));
		}

		// ------------------------------------------------------------------------------
		// 備考
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("備考");
		} else {
			buf.add("　");
		}

		// ------------------------------------------------------------------------------
		// リビジョン
		// ------------------------------------------------------------------------------
		if (m == null) {
			buf.add("リビジョン");
		} else {
			buf.add(cx.params.get("-revision")[0]);
		}

		// ------------------------------------------------------------------------------

		return buf;
	}

	/**
	 * 区切り線を出力します。
	 * @param label ラベル
	 */
	private static void separator(String label) {
		if (label != null) {
			label = " " + label + " ";
		}
		cx.out.println();
		cx.out.println("#-----------------------------------------------------------");
		cx.out.println("# " + label);
		cx.out.println("#-----------------------------------------------------------");
		cx.out.println();
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
		String tmp;

		if (value == null) {
			tmp = "－";
		} else {
			tmp = value.toString();
		}
		printRec(Arrays.asList(new String[]{label, tmp}));
	}


	private static void printRec(List<String> rec) {
		cx.out.println(
			join(
				map(rec, new MapProc<String>() {
					public String proc(String value) {
						if (value == null) {
							return "－";
						}
						// 行頭・行末の改行を削除
						value = value.replaceAll("^[ \t\r\n]*|[ \t\r\n]*$", "");

						// 行頭・行末の改行を削除
						value = value.replaceAll("^[ \t\r\n]*|[ \t\r\n]*$", "");
						
						if (value.length() == 0) {
							return "－";
						}
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

	/**
	 * リストの各要素に対して変換処理を適用し、変換後のリストを返す。
	 * 元のリストは変更されない。
	 * @param arr
	 * @param proc 変換処理
	 * @return
	 */
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

	/**
	 * リストに対してフィルターを適用し、該当する要素のリストを返す。
	 * 元のリストは変更されない。
	 * @param arr
	 * @param filter フィルター処理
	 * @return
	 */
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
	/**
	 * リストに対してフィルターを適用し、該当する要素のリストを返す。
	 * 該当する要素は元のリストから削除される。
	 * @param arr
	 * @param filter フィルター処理
	 * @return
	 */
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

	// HTMLタグおよび{@link xxx}形式のタグを除去
	private static String deleteHtmlTag(String text) {
		return text.replaceAll("<[^>]*>", "")	// タグ削除
				.replaceAll("[\r\n \t]*$", "")		// 末尾空白削除
				.replaceAll("\n([ \t\r]*\n)+", "\n\n")		// 連続する空行を１つの空行にする
				.replaceAll("\\{@[^ }]* +([^ }]*)\\}|\\{@[^ }]* +[^ }]* +([^}]*)\\}", "$1$2"); // @link形式のタグを削除;
	}

	// Javadocコメント形式のテキストに含まれる行頭の空白文字を削除
	private static String description(String commentText) {
		return commentText.replaceAll("^ ", "").replaceAll("\n ", "\n").replaceAll("\n$", "");
	}

	private static String fullComment(String rawCommentText) {
		if (rawCommentText == null || rawCommentText.length() == 0) {
			return "－";
		} else {
			return "/**\n *" + rawCommentText.replaceAll("\n", "\n *") + "/";
		}
	}

	// javadocの引数の型や戻り値の型に記載する型名を編集
	// パッケージ修飾…なし
	// パラメータ型引数…あり
	// 配列表示…あり
	private static String typeName(Type t) {
		StringBuffer buf = new StringBuffer(t.typeName());
		if (t.asParameterizedType() != null) {
			buf.append("<");
			buf.append(join(
				map(Arrays.asList((Object[]) t.asParameterizedType().typeArguments()), new MapProc<Object>() {
					@Override
					public Object proc(Object value) {
						Type t = (Type) value;
						return typeName(t);
					}
				}), ","
			));
			buf.append(">");
		}
		buf.append(t.dimension());

		return buf.toString();
	}
	
	// パラメータ引数名を付与したクラス名を返す
	private static String genericClassName(ClassDoc c) {
		StringBuffer ret = new StringBuffer(c.name());
		if (c.typeParameters().length > 0) {
			ret.append("<");
			ret.append(join(
				map(Arrays.asList((Object[]) c.typeParameters()), new MapProc<Object>() {
					@Override
					public Object proc(Object value) {
						TypeVariable tv = (TypeVariable) value;
						return tv.toString();
					}
				}), ","
			));
			ret.append(">");
		}
		
		return ret.toString();
	}

	private static List<String> annotation(ProgramElementDoc m, Set<String> annoSet) {
		List<String> b = new ArrayList<String>();
		for (AnnotationDesc an : m.annotations()) {
			// アノテーションが処理済の場合スキップ
			if (annoSet.contains(an.toString())) {
				continue;
			}
			StringBuffer sb = new StringBuffer("@");
			sb.append(an.annotationType().name());
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
			b.add(sb.toString());
		}

		return b;
	}

	// MethodDoc#overridenMethod はインターフェースメソッドの実装は判定しないので、
	// インターフェースを実装する場合を追加で確認
	private static MethodDoc overrides(MethodDoc m) {
		MethodDoc ret = m.overriddenMethod();
		if (ret == null) {
			for (ClassDoc c = m.containingClass(); c != null; c = c.superclass()) {
				for (ClassDoc itf : c.interfaces()) {
					ret = overrides_inner(m, itf);
					if (ret != null) {
						break;
					}
				}
				if (ret != null) {
					break;
				}
			}
		}
		return ret;
	}

	// メソッドmがインターフェースcのメソッドを実装するかどうかを判定
	private static MethodDoc overrides_inner(MethodDoc m, ClassDoc c) {
		for (MethodDoc m2 : c.methods()) {
			if (m.overrides(m2)) {
				return m2;
			}
		}

		for (ClassDoc itf : c.interfaces()) {
			MethodDoc tmp = overrides_inner(m, itf);
			if (tmp != null) {
				return tmp;
			}
		}

		return null;
	}

	private static class DocComparator implements Comparator<Doc> {

		@Override
		public int compare(Doc o1, Doc o2) {
			
			// まずアクセス修飾子で比較
			Integer ac1 = accessTypeNumber(o1);
			Integer ac2 = accessTypeNumber(o2);
			if (ac1 != ac2) {
				return ac1.compareTo(ac2);
			}
			
			// 次に名前で比較
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
			// ...まずCaseInsensitiveで比較
			int ret = v1.toString().toLowerCase().compareTo(v2.toString().toLowerCase());

			// 一致する場合は念のためCaseSensitiveで比較
			if (ret == 0) {
				ret = v1.toString().compareTo(v2.toString());
			}
			return ret;
		}

	}
	
	private static int accessTypeNumber(Doc d) {
		if (d instanceof ProgramElementDoc) {
			ProgramElementDoc p = (ProgramElementDoc) d;
			if (p.isPublic()) {
				return 1;
			}
			if (p.isProtected()) {
				return 2;
			}
			if (p.isPackagePrivate()) {
				return 3;
			}
			if (p.isPrivate()) {
				return 4;
			}
			throw new IllegalStateException("unknown access of " + d);
		} else {
			return 0;
		}
	}

	// タブ文字をtabstopに指定したタブストップ位置までのスペースに置換します。
	private static String tabConvert(String str, int tabstop) {
		StringBuffer sb = new StringBuffer();
		int column = 0;
		for (char c : str.toCharArray()) {
			if (c == '\t') {
				int cc = tabstop - (column % tabstop);
				for (int i = 0; i < cc; i++) {
					sb.append(" ");
				}
				column += cc;
			} else if (c == '\n') {
				sb.append(c);
				column = 0;
			} else {
				sb.append(c);
				column++;
			}
		}
		return sb.toString();
	}

	/**
	 * @deprecated
	 */
	@Deprecated
	public void hoge() {

	}
}
