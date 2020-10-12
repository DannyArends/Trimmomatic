package org.usadellab.trimmomatic;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.usadellab.trimmomatic.fastq.FastqParser;
import org.usadellab.trimmomatic.fastq.FastqRecord;
import org.usadellab.trimmomatic.fastq.FastqSerializer;
import org.usadellab.trimmomatic.fastq.trim.Trimmer;
import org.usadellab.trimmomatic.fastq.trim.TrimmerFactory;
import org.usadellab.trimmomatic.threading.BlockOfRecords;
import org.usadellab.trimmomatic.threading.BlockOfWork;
import org.usadellab.trimmomatic.threading.ParserWorker;
import org.usadellab.trimmomatic.threading.SerializerWorker;
import org.usadellab.trimmomatic.threading.TrimLogWorker;
import org.usadellab.trimmomatic.threading.TrimStatsWorker;


public class TrimmomaticPE
{

	/**
	 * Trimmomatic: The FASTQ trimmer
	 * 
	 * CROP:<LENGTH> Crop the read to specified length, by cutting off the right end
	 * LEADING:<QUAL> Trim the read by cutting off the left end while below specified quality
	 * TRAILING:<QUAL> Trim the read by cutting off the right end while below specified quality
	 * SLIDINGWINDOW:<QUAL>:<COUNT> Trim the read once the total quality of COUNT bases drops below QUAL, then trim trailing bases below QUAL 
	 * 
	 * MINLEN:<LENGTH> Drop the read if less than specified length 
	 */

	
	public TrimmomaticPE()
	{
	
	}

	public void processSingleThreaded(FastqParser parser1, FastqParser parser2, 
			FastqSerializer serializer1P, FastqSerializer serializer1U, FastqSerializer serializer2P, FastqSerializer serializer2U, 
			Trimmer trimmers[], PrintStream trimLogStream) throws IOException
	{
		TrimStats stats=new TrimStats();
	
		FastqRecord originalRecs[]=new FastqRecord[2];
		FastqRecord recs[]=new FastqRecord[2];

		while(parser1.hasNext() && parser2.hasNext())
			{
			originalRecs[0]=recs[0]=parser1.next();
			originalRecs[1]=recs[1]=parser2.next();
		
			for(int i=0;i<trimmers.length;i++)
				{
				recs=trimmers[i].processRecords(recs);
				}
		
			if(recs[0]!=null && recs[1]!=null)
				{
				serializer1P.writeRecord(recs[0]);
				serializer2P.writeRecord(recs[1]);
				}
			else if(recs[0]!=null)
				serializer1U.writeRecord(recs[0]);
			else if(recs[1]!=null)
				serializer2U.writeRecord(recs[1]);
		
			stats.logPair(originalRecs, recs);
			
			if(trimLogStream!=null)
				{
				for(int i=0;i<originalRecs.length;i++)
					{
					int length=0;
					int startPos=0;
					int endPos=0;
					int trimTail=0;
				
					if(recs[i]!=null)
						{
						length=recs[i].getSequence().length();
						startPos=recs[i].getHeadPos();
						endPos=length+startPos;
						trimTail=originalRecs[i].getSequence().length()-endPos;
						}
				
					trimLogStream.printf("%s %d %d %d %d\n",originalRecs[i].getName(),length,startPos,endPos,trimTail);
					}
				}
		}
		
		System.out.println(stats.getStatsPE());
	}
	
	
	public void processMultiThreaded(FastqParser parser1, FastqParser parser2, 
			FastqSerializer serializer1P, FastqSerializer serializer1U, FastqSerializer serializer2P, FastqSerializer serializer2U, 
			Trimmer trimmers[], PrintStream trimLogStream, int threads) throws IOException
	{
		ArrayBlockingQueue<List<FastqRecord>> parser1Queue=new ArrayBlockingQueue<List<FastqRecord>>(threads);
		ArrayBlockingQueue<List<FastqRecord>> parser2Queue=new ArrayBlockingQueue<List<FastqRecord>>(threads);
		
		ArrayBlockingQueue<Runnable> taskQueue=new ArrayBlockingQueue<Runnable>(threads*2);
		
		ArrayBlockingQueue<Future<BlockOfRecords>> serializerQueue1P=new ArrayBlockingQueue<Future<BlockOfRecords>>(threads*5);
		ArrayBlockingQueue<Future<BlockOfRecords>> serializerQueue1U=new ArrayBlockingQueue<Future<BlockOfRecords>>(threads*5);
		ArrayBlockingQueue<Future<BlockOfRecords>> serializerQueue2P=new ArrayBlockingQueue<Future<BlockOfRecords>>(threads*5);
		ArrayBlockingQueue<Future<BlockOfRecords>> serializerQueue2U=new ArrayBlockingQueue<Future<BlockOfRecords>>(threads*5);
		
		ParserWorker parserWorker1=new ParserWorker(parser1, parser1Queue);
		ParserWorker parserWorker2=new ParserWorker(parser2, parser2Queue);
		
		Thread parser1Thread=new Thread(parserWorker1);
		Thread parser2Thread=new Thread(parserWorker2);
		
		ThreadPoolExecutor taskExec=new ThreadPoolExecutor(threads,threads,0,TimeUnit.SECONDS, taskQueue);
		
		SerializerWorker serializerWorker1P=new SerializerWorker(serializer1P, serializerQueue1P, 0);
		SerializerWorker serializerWorker1U=new SerializerWorker(serializer1U, serializerQueue1U, 1);
		SerializerWorker serializerWorker2P=new SerializerWorker(serializer2P, serializerQueue2P, 2);
		SerializerWorker serializerWorker2U=new SerializerWorker(serializer2U, serializerQueue2U, 3);
		
		Thread serializer1PThread=new Thread(serializerWorker1P);
		Thread serializer1UThread=new Thread(serializerWorker1U);
		Thread serializer2PThread=new Thread(serializerWorker2P);
		Thread serializer2UThread=new Thread(serializerWorker2U);
		
		ArrayBlockingQueue<Future<BlockOfRecords>> trimStatsQueue=new ArrayBlockingQueue<Future<BlockOfRecords>>(threads*5);
		TrimStatsWorker statsWorker=new TrimStatsWorker(trimStatsQueue);
		Thread statsThread=new Thread(statsWorker);
		
		ArrayBlockingQueue<Future<BlockOfRecords>> trimLogQueue=null;
		TrimLogWorker trimLogWorker=null;
		Thread trimLogThread=null;
		
		if(trimLogStream!=null)
			{
			trimLogQueue=new ArrayBlockingQueue<Future<BlockOfRecords>>(threads*5);
			trimLogWorker=new TrimLogWorker(trimLogStream, trimLogQueue);
			trimLogThread=new Thread(trimLogWorker);
			trimLogThread.start();
			}
		
		parser1Thread.start();
		parser2Thread.start();
		
		serializer1PThread.start();
		serializer1UThread.start();
		serializer2PThread.start();
		serializer2UThread.start();
		
		statsThread.start();
		
		boolean done1=false,done2=false;
		
		List<FastqRecord> recs1=null;
		List<FastqRecord> recs2=null;
	
		try	{
			while(!done1 || !done2)
				{
				if(!done1)
					{
					recs1=null;
					while(recs1==null)
						recs1=parser1Queue.poll(1, TimeUnit.SECONDS);

					if(recs1==null || recs1.size()==0)
						done1=true;
					}
				if(!done2)
					{
					recs2=null;
					while(recs2==null)
						recs2=parser2Queue.poll(1, TimeUnit.SECONDS);

					if(recs2==null || recs2.size()==0)
						done2=true;
					}
		
				BlockOfRecords bor=new BlockOfRecords(recs1, recs2);
				BlockOfWork work=new BlockOfWork(trimmers, bor, true, trimLogStream!=null);
	
				while(taskQueue.remainingCapacity()<1)
					Thread.sleep(100);
					
				Future<BlockOfRecords> future=taskExec.submit(work);
				
				serializerQueue1P.put(future);
				serializerQueue1U.put(future);
				serializerQueue2P.put(future);
				serializerQueue2U.put(future);
				
				trimStatsQueue.put(future);
				
				if(trimLogQueue!=null)
					trimLogQueue.put(future);
				}
			
			parser1Thread.join();
			parser2Thread.join();

			parser1.close();
			parser2.close();
			
			taskExec.shutdown();
			taskExec.awaitTermination(1, TimeUnit.HOURS);

			serializer1PThread.join();
			serializer1UThread.join();
			serializer2PThread.join();
			serializer2UThread.join();
			
			if(trimLogThread!=null)
				trimLogThread.join();
			
			statsThread.join();
			System.out.println(statsWorker.getStats().getStatsPE());		
			}
		catch(InterruptedException e)
			{
			throw new RuntimeException(e);
			}
	}
	
	
	
	public void process(File input1, File input2, 
			File output1P, File output1U, File output2P, File output2U, Trimmer trimmers[], 
			int phredOffset, File trimLog, int threads) throws IOException
	{
		FastqParser parser1=new FastqParser(phredOffset);
		parser1.parse(input1);

		FastqParser parser2=new FastqParser(phredOffset);		
		parser2.parse(input2);
		
		FastqSerializer serializer1P=new FastqSerializer();
		serializer1P.open(output1P);
		
		FastqSerializer serializer1U=new FastqSerializer();
		serializer1U.open(output1U);
		
		FastqSerializer serializer2P=new FastqSerializer();
		serializer2P.open(output2P);
		
		FastqSerializer serializer2U=new FastqSerializer();
		serializer2U.open(output2U);
		
		
		PrintStream trimLogStream=null;
		if(trimLog!=null)
			trimLogStream=new PrintStream(trimLog);
		
		if(threads==1)
			processSingleThreaded(parser1, parser2, serializer1P, serializer1U, serializer2P, serializer2U, trimmers, trimLogStream);
		else
			processMultiThreaded(parser1, parser2, serializer1P, serializer1U, serializer2P, serializer2U, trimmers, trimLogStream, threads);
		
		serializer1P.close();
		serializer1U.close();
		serializer2P.close();
		serializer2U.close();
		
		if(trimLogStream!=null)
			trimLogStream.close();
	}
	


	public static void main(String[] args) throws IOException
	{
		int argIndex=0;
		int phredOffset=64;
		int threads=1;
		
		boolean badOption=false;
		
		File trimLog=null;
		
		while(argIndex < args.length && args[argIndex].startsWith("-"))
			{
			String arg=args[argIndex++];
			if(arg.equals("-phred33"))
				phredOffset=33;
			else if(arg.equals("-phred64"))
				phredOffset=64;
			else if(arg.equals("-threads"))
				threads=Integer.parseInt(args[argIndex++]);
			else if(arg.equals("-trimlog"))
				{
				if(argIndex<args.length)
					trimLog=new File(args[argIndex++]);
				else
					badOption=true;
				}
			else 
				{
				System.out.println("Unknown option "+arg);
				badOption=true;
				}
			}
	
		if(args.length-argIndex<7 || badOption)
			{
			System.out.println("Usage: TrimmomaticPE [-threads <threads>] [-phred33|-phred64] [-trimlog <trimLogFile>] <inputFile1> <inputFile2> <outputFile1P> <outputFile1U> <outputFile2P> <outputFile2U> <trimmer1>...");
			System.exit(1);
			}
		
		System.out.print("TrimmomaticPE: Started with arguments:");
		for(String arg: args)
			System.out.print(" "+arg);
		System.out.println();
				
		File input1=new File(args[argIndex++]);
		File input2=new File(args[argIndex++]);
		
		File output1P=new File(args[argIndex++]);
		File output1U=new File(args[argIndex++]);
		
		File output2P=new File(args[argIndex++]);
		File output2U=new File(args[argIndex++]);
		
		TrimmerFactory fac=new TrimmerFactory();
		Trimmer trimmers[]=new Trimmer[args.length-argIndex];
		
		for(int i=0;i<trimmers.length;i++)
			trimmers[i]=fac.makeTrimmer(args[i+argIndex]);
		
		TrimmomaticPE tm=new TrimmomaticPE();
		tm.process(input1,input2,output1P,output1U,output2P,output2U,trimmers,phredOffset,trimLog, threads);
		
		System.out.println("TrimmomaticPE: Completed successfully");
	}

}
