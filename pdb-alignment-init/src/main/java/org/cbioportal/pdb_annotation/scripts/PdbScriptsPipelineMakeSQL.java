package org.cbioportal.pdb_annotation.scripts;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.io.FileUtils;
import org.cbioportal.pdb_annotation.util.Constants;
import org.cbioportal.pdb_annotation.util.blast.BlastDataBase;
import org.cbioportal.pdb_annotation.util.blast.BlastOutput;
import org.cbioportal.pdb_annotation.util.blast.BlastOutputIterations;
import org.cbioportal.pdb_annotation.util.blast.BlastResult;
import org.cbioportal.pdb_annotation.util.blast.Hit;
import org.cbioportal.pdb_annotation.util.blast.Hsp;
import org.cbioportal.pdb_annotation.util.blast.Iteration;
import org.cbioportal.pdb_annotation.util.blast.IterationHits;

/**
 * SQL Insert statments Generation
 * 
 * @author Juexin Wang
 *
 */
public class PdbScriptsPipelineMakeSQL {
	private BlastDataBase db;
	private int matches;
	private int ensembl_file_count;
	
	PdbScriptsPipelineMakeSQL(PdbScriptsPipelineStarter app){
		this.db = app.db;
		this.matches=app.matches;
		this.ensembl_file_count=app.ensembl_file_count;
	}
	
	/**
	 * parse XML blast results to INSERT SQL file
	 * 
	 * @param choose
	 *            0 for mem efficiency, 1 for disk efficiency
	 * @return
	 */
	public boolean parse2sql(int choose) {
		switch (choose) {
		case 0:
			// multiple input, multiple sql generated incrementally
			if (this.ensembl_file_count == -1) {
				parseblastresultsSmallMem();
			} else {
				HashMap ensemblHm = new HashMap();
				HashMap pdbHm = new HashMap();
				for (int i = 0; i < this.ensembl_file_count; i++) {
					parseblastresultsSmallMem(i, ensemblHm, pdbHm);
				}
			}
			break;
		case 1:
			// test for small datasets: single input, single sql generated in one time
			List<BlastResult> outresults = parseblastresultsSingle();
			for (int i = 0; i <= outresults.size(); i++) {
				System.out.println(outresults.get(i).toString());
			}
			generateSQLstatementsSingle(outresults);
			break;

		default:
			break;
		}
		return true;
	}
	
	/**
	 * parse Single blast XML results, output to SQL file incrementally
	 * 
	 * @return Success/Failure
	 */
	public boolean parseblastresultsSmallMem() {
		try {
			System.out.println("[BLAST] Read blast results from xml file...");

			File blastresults = new File(Constants.workspace + this.db.resultfileName);
			File outputfile = new File(Constants.workspace + Constants.sql_insert_file);
			HashMap ensemblHm = new HashMap();
			HashMap pdbHm = new HashMap();
			int count = parsexml(blastresults, outputfile, ensemblHm, pdbHm);
			this.matches = count;
			System.out.println("[BLAST] Total Input Queries = " + this.matches);
		} catch (Exception ee) {
			System.err.println("[BLAST] Error Parsing BLAST Result");
			ee.printStackTrace();
		}
		return true;
	}

	/**
	 * parse multiple blast XML results, output to SQL file incrementally
	 * 
	 * @param filecount:
	 *            id of the multiple files
	 * @param ensemblHm
	 * @param pdbHm
	 * @return
	 */
	public boolean parseblastresultsSmallMem(int filecount, HashMap ensemblHm, HashMap pdbHm) {
		try {
			System.out.println("[BLAST] Read blast results from " + filecount + "th xml file...");
			File blastresults = new File(Constants.workspace + this.db.resultfileName + "." + filecount);
			File outputfile;
			// Check whether multiple files existed
			if (this.ensembl_file_count != -1) {
				outputfile = new File(Constants.workspace + Constants.sql_insert_file + "." + filecount);
			} else {
				outputfile = new File(Constants.workspace + Constants.sql_insert_file);
			}
			int count = parsexml(blastresults, outputfile, ensemblHm, pdbHm);			
			this.matches = this.matches + count;
			System.out.println("[BLAST] Input Queries after parsing " + filecount + "th xml : " + this.matches);
		} catch (Exception ee) {
			System.err.println("[BLAST] Error Parsing BLAST Result");
			ee.printStackTrace();
		}
		return true;
	}
	
	/**
	 * main body of parsing xml
	 * @param blastresults
	 * @param outputfile
	 * @param ensemblHm
	 * @param pdbHm
	 * @return
	 */
	public int parsexml(File blastresults, File outputfile, HashMap ensemblHm, HashMap pdbHm){
		int count = 0;
		try{
			JAXBContext jc = JAXBContext.newInstance("org.cbioportal.pdb_annotation.util.blast");
			Unmarshaller u = jc.createUnmarshaller();
			u.setSchema(null);
			BlastOutput blast = (BlastOutput) u.unmarshal(blastresults);
			List<BlastResult> results = new ArrayList<BlastResult>();
			BlastOutputIterations iterations = blast.getBlastOutputIterations();
			System.out.println("[BLAST] Start parsing results...");			
			for (Iteration iteration : iterations.getIteration()) {
				String querytext = iteration.getIterationQueryDef();
				IterationHits hits = iteration.getIterationHits();
				for (Hit hit : hits.getHit()) {
					results.add(parseSingleAlignment(querytext, hit, count));
					if (count % Constants.sql_insert_output_interval == 0) {
						// Once get the criteria, output contents to the SQL file
						genereateSQLstatementsSmallMem(results, ensemblHm, pdbHm, count, outputfile);
						results.clear();
					}
					count++;
				}
			}
			// output remaining contents to the SQL file
			genereateSQLstatementsSmallMem(results, ensemblHm, pdbHm, count, outputfile);			
		}catch (Exception ee) {
			System.err.println("[BLAST] Error Parsing BLAST Result");
			ee.printStackTrace();
		}
		return count;
	}
	
	/**
	 * generate SQL insert text to Table ensembl_entry
	 * @param br
	 * @return
	 */
	public String makeTable_ensembl_entry_insert(BlastResult br){
		String[] strarrayQ = br.getQseqid().split("\\s+");
		String str = "INSERT INTO `ensembl_entry`(`ensemblid`,`ensemblgene`,`ensembltranscript`) VALUES('"
				+ strarrayQ[0] + "', '" + strarrayQ[3].split(":")[1] + "', '" + strarrayQ[4].split(":")[1]
				+ "');\n";
		return str;		 
	}
	
	/**
	 * generate SQL insert text to Table pdb_entry
	 * @param br
	 * @return
	 */
	public String makeTable_pdb_entry_insert(BlastResult br){
		String[] strarrayS = br.getSseqid().split("_");
		String str = "INSERT INTO `pdb_entry` (`pdbno`,`pdbid`,`chain`) VALUES ('" + br.getSseqid() + "', '"
				+ strarrayS[0] + "', '" + strarrayS[1] + "');\n";
		return str;
	}
	
	/**
	 * generate SQL insert text to Table pdb_ensembl_alignment
	 * @param br
	 * @return
	 */
	public String makeTable_pdb_ensembl_insert(BlastResult br){
		String[] strarrayQ = br.getQseqid().split("\\s+");
		String[] strarrayS = br.getSseqid().split("_");
		String str = "INSERT INTO `pdb_ensembl_alignment` (`pdbno`,`pdbid`,`chain`,`ensemblid`,`pdbfrom`,`pdbto`,`ensemblfrom`,`ensemblto`,`evalue`,`bitscore`,`identity`,`identp`,`ensemblalign`,`pdbalign`,`midlinealign`)VALUES ('"
				+ br.getSseqid() + "','" + strarrayS[0] + "','" + strarrayS[1] + "','" + strarrayQ[0] + "',"
				+ br.getqStart() + "," + br.getqEnd() + "," + br.getsStart() + "," + br.getsEnd() + ",'"
				+ br.getEvalue() + "'," + br.getBitscore() + "," + br.getIdent() + "," + br.getIdentp() + ",'"
				+ br.getEnsembl_align() + "','" + br.getPdb_align() + "','" + br.getMidline_align() + "');\n";		
		return str;
	}

	/**
	 * Parse list of String blast results to input SQL statements, time and memory consuming for huge files
	 * Use
	 * 
	 * @param outresults
	 *            List<BlastResult>
	 * @return Success/Failure
	 */
	boolean generateSQLstatementsSingle(List<BlastResult> results) {
		try {
			System.out.println("[SHELL] Start Write insert.sql File...");
			File file = new File(Constants.workspace + Constants.sql_insert_file);
			FileUtils fu = new FileUtils();

			// HashMap ensemblHm and pdbHm are designed to avoid duplication of
			// primary keys in SQL
			// if we already have the entry, do nothing; otherwise generate the
			// SQL and add the keys into the HashMap
			HashMap ensemblHm = new HashMap();
			HashMap pdbHm = new HashMap();
			List<String> outputlist=makeSQLText(results, ensemblHm, pdbHm);
			fu.writeLines(file, outputlist);
			System.out.println("[SHELL] Write insert.sql Done");
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return true;
	}

	/**
	 * Parse multiple list of String blast results to multiple input SQL statements
	 * 
	 * @param List<BlastResult>
	 *            results
	 * @param ensemblHm
	 * @param pdbHm
	 * @param count
	 * @param outputfile
	 * @return
	 */
	public boolean genereateSQLstatementsSmallMem(List<BlastResult> results, HashMap ensemblHm, HashMap pdbHm,
			int count, File outputfile) {
		try {
			System.out.println("[SHELL] Start Write insert.sql File from Alignment " + count + "...");
			FileUtils fu = new FileUtils();
			// check, if starts, make sure it is empty
			if (count == 0) {
				if (outputfile.exists()) {
					outputfile.delete();
				}
			}
			List<String> outputlist=makeSQLText(results, ensemblHm, pdbHm);
			fu.writeLines(outputfile, outputlist, "", true);
			outputlist = null;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return true;
	}
	
	/**
	 * main body of generate SQL Insert text
	 * @param results
	 * @param ensemblHm
	 * @param pdbHm
	 * @return
	 */
	List<String> makeSQLText(List<BlastResult> results, HashMap ensemblHm, HashMap pdbHm){
		List<String> outputlist = new ArrayList<String>();
		for (BlastResult br : results) {
			String str = "";
			if (ensemblHm.containsKey(br.getQseqid())) {
				// do nothing
			} else {
				outputlist.add(makeTable_ensembl_entry_insert(br));
				ensemblHm.put(br.getQseqid(), "");
			}
			if (pdbHm.containsKey(br.getSseqid())) {
				// do nothing
			} else {
				outputlist.add(makeTable_pdb_entry_insert(br));
				pdbHm.put(br.getSseqid(), "");
			}
			outputlist.add(makeTable_pdb_ensembl_insert(br));
		}
		return outputlist;
	}


	/**
	 * Parse one single file of blast results to list of String, time and memory
	 * consuming for huge files
	 * 
	 * @return List<BlastResult>
	 */
	public List<BlastResult> parseblastresultsSingle() {
		List<BlastResult> results = new ArrayList<BlastResult>(this.matches);
		try {
			System.out.println("[BLAST] Read blast results from xml file...");
			File blastresults = new File(Constants.workspace + this.db.resultfileName);
			JAXBContext jc = JAXBContext.newInstance("org.cbioportal.pdb_annotation.util.blast");
			Unmarshaller u = jc.createUnmarshaller();
			u.setSchema(null);
			BlastOutput blast = (BlastOutput) u.unmarshal(blastresults);

			BlastOutputIterations iterations = blast.getBlastOutputIterations();
			int count = 1;
			for (Iteration iteration : iterations.getIteration()) {
				String querytext = iteration.getIterationQueryDef();
				IterationHits hits = iteration.getIterationHits();
				for (Hit hit : hits.getHit()) {
					BlastResult br = parseSingleAlignment(querytext, hit, count);
					results.add(br);
					count++;
				}
			}
			this.matches = count - 1;
			System.out.println("[BLAST] Total Input Queries = " + this.matches);
		} catch (Exception ee) {
			System.err.println("[BLAST] Error Parsing BLAST Result");
			ee.printStackTrace();
		}
		return results;
	}

	/**
	 * Parse XML structure into Object BlastResult
	 * 
	 * @param querytext
	 * @param hit
	 * @param count
	 * @return
	 */
	public BlastResult parseSingleAlignment(String querytext, Hit hit, int count) {
		BlastResult br = new BlastResult(count);
		br.qseqid = querytext;
		br.sseqid = hit.getHitDef().split("\\s+")[0];
		for (Hsp tmp : hit.getHitHsps().getHsp()) {
			br.ident = Double.parseDouble(tmp.getHspPositive());
			br.identp = Double.parseDouble(tmp.getHspIdentity());
			br.evalue = Double.parseDouble(tmp.getHspEvalue());
			br.bitscore = Double.parseDouble(tmp.getHspBitScore());
			br.qStart = Integer.parseInt(tmp.getHspQueryFrom());
			br.qEnd = Integer.parseInt(tmp.getHspQueryTo());
			br.sStart = Integer.parseInt(tmp.getHspHitFrom());
			br.sEnd = Integer.parseInt(tmp.getHspHitTo());
			br.ensembl_align = tmp.getHspQseq();
			br.pdb_align = tmp.getHspHseq();
			br.midline_align = tmp.getHspMidline();
		}
		return br;
	}
}