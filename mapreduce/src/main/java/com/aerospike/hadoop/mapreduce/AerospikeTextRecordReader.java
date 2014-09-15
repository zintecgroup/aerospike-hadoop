/* 
 * Copyright 2014 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more
 * contributor license agreements.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.aerospike.hadoop.mapreduce;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;

import org.apache.hadoop.mapred.RecordReader;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;

public class AerospikeTextRecordReader implements RecordReader<LongWritable, Text> {

	private ASSCanReader in;

	private LinkedBlockingQueue<Record> queue = new LinkedBlockingQueue<Record>();
	private boolean isScanFinished = false;
	private boolean isError = false;
	private boolean isScanRunning = false;
	
	public class CallBack implements ScanCallback {
        @Override
        public void scanCallback(Key key, Record record) {
			try {
				queue.put(record);
			} catch (Exception e) {
				System.out.println("scanCallback: Exception raised while inserting record into queue.");
			}
        }
	}

	/*
	private static class ScanObject {
		public int n_objects;
		public ArrayList<String> startValue;
		public ArrayList<String> startBin;
		public boolean failed;
		public boolean delay;
		public boolean nobindata;
		public int n_keys;
		public int num_bins;
		public boolean[] key_found;
	}
	*/

	public class ASSCanReader extends java.lang.Thread {
		String node;
		String host;
		int port;
		String namespace;
		String setName;

		ASSCanReader(String node, String host, int port, String ns, String setName) {
			this.node = node;
			this.host = host;
			this.port = port;
			this.namespace = ns;
			this.setName = setName;
		}

		public void run() {
			AerospikeClient client = null;
            try {
                client = new AerospikeClient(host, port);
            }
            catch (AerospikeException ex) {
				System.out.println("Exception opening client " + ex);
                System.exit(1);
            }

			if (client == null) {
				System.out.println(" Cluster " + host + ":" + port + " could not be contacted.");
				System.out.println(" Unable to scan cluster nodes.");
				isError = true;
				return;
			}

			// client.connect();
			try {
				// Sleep so that the partition hashmap is created by the client
				Thread.sleep(3000);
			} catch (Exception e) {
				System.out.println(" Exception raised when sleeping " + e);
			}

            try {
                ScanPolicy scanPolicy = new ScanPolicy();
                CallBack cb = new CallBack();
                isScanRunning = true;
                client.scanNode(scanPolicy, node, namespace, setName, cb);
                isScanFinished = true;
                // System.out.println("Scan finished");
            }
            catch (AerospikeException ex) {
				System.out.println("Exception opening client " + ex);
                System.exit(1);
            }
		}
	}

	public AerospikeTextRecordReader(Configuration job, AerospikeSplit split)
        throws IOException {
		final String node = split.getNode();
		final String host = split.getHost();
		final int port = split.getPort();
		final String namespace = split.getNameSpace();
		final String setName = split.getSetName();
		in = new ASSCanReader(node, host, port, namespace, setName);
		in.start();
		
		// System.out.println("node: " + node);
	}

	public LongWritable createKey() {
		return new LongWritable();
	}

	public Text createValue() {
		return new Text();
	}

	public synchronized boolean next(LongWritable key, Text value)
			throws IOException {
		final int waitMSec = 1000;
		int trials = 5;

		try {
			Record rec;
			while (true) {
				if (isError)
					return false;
				
				if (!isScanRunning) {
					Thread.sleep(100);
					continue;
				}
			
				if (!isScanFinished && queue.size() == 0) {
					if (trials == 0) {
						System.out.println("Scan timeout");
						return false;
					}
					System.out.println("Queue empty: waiting...");
					Thread.sleep(waitMSec);
					trials--;
				} else if (isScanFinished && queue.size() == 0) {
					return false;
				} else if (queue.size() != 0) {
					rec = queue.take();
					break;
				}
				
			}

			key.set(1);
			System.out.println("next: " + rec.toString());
			value.set("" + rec.generation);
		} catch (Exception e) {
			System.out.println("Exception");
		}
		return true;
	}

	public float getProgress() {
		if (isScanFinished)
			return 1.0f;
		else
			return 0.0f;
	}

	public synchronized long getPos() throws IOException {
		return 0;
	}

	public synchronized void close() throws IOException {
		if (in != null) {
			try {
				in.join();
			} catch (Exception e) {
				System.out.println("Scan thread got interrupted.");
			}
			in = null;
		}
	}
}
