/*

GrainBuf

Meta_Buffer:readChannel

Buffer.readChannel (server, path, startFrame: 0, numFrames: -1, channels, action, bufnum)

channels:[0]
channels:[1] 
or channels:[0,1]

ContiguousBlockAllocator

*/

// loads each channel into adjcent buffers on the server ////////////////////////////////

LNX_BufferArray {
	
	var <>verbose=false;
	
	var <buffers, <numChannels, <numFrames, <sampleRate, <duration, <sampleData;
	
	*read {|server,path,action| ^super.new.init(server,path,action) }
	
	init{ |server, path, action|
		
		var bufnum;
		var soundFile = SoundFile();
		var done;
		
		soundFile.openRead(path);
		numChannels = soundFile.numChannels;
		numFrames   = soundFile.numFrames;
		sampleRate  = soundFile.sampleRate;
		duration    = soundFile.duration;
		//sampleData  = FloatArray[0]; // temp
		
		sampleData  = FloatArray.fill(numFrames*numChannels,0); // causes lates with large samples
		
		// fast but causes lates
		soundFile.readData(sampleData);
		
//		// slow causes less lates
//		soundFile.readByChunks(action:{|data| 
//			//sampleData=data;
//			if (verbose) {path.postln};
//			soundFile.close;
//		}, floatArray:sampleData);	
		
		bufnum = server.bufferAllocator.alloc(numChannels); // make sure buffers are adj
		
		done = 1 ! numChannels; // reverse of normal 0 = done
		
		// maybe action should only call after all have loaded
		numChannels.do{|i|
			buffers = buffers.add(

				// fast but caauses lates
				Buffer.readChannel (server, path,0, -1, [i], {|buf|
					done[i] = 0; // when sum of done is zero, all buffers have loaded
					if (done.sum==0) {action.value(this)}
				}, bufnum+i );

//				// slow but causes less lates
//				Buffer.readChannelByChunk (server, path,0, -1, 0, false, [i], bufnum+i , {|buf|
//					done[i] = 0; // when sum of done is zero, all buffers have loaded
//					if (done.sum==0) {action.value(this)}
//				});


			)
		};
		
	}
	
	// play this buffer
	play {|loop = false, mul = 1, offset=0|
	 	if (buffers.notNil) {
		 	if (numChannels==1) {
		 		^buffers[0].playMono(loop,mul,offset);
		 	}{
			 	^buffers[0].playStereo(loop,mul,offset);
		 	}
		 }{ ^nil }
	}
	
	getWithRef{|idx,action,ref1,ref2,channel|
		buffers[channel].getWithRef(idx,action,ref1,ref2)
	}
	bufnum {|i=0| if (buffers[i].notNil) {^buffers[i].bufnum }{^nil} }
	
	free{
		buffers.do(_.free);
		sampleData = nil;
	}
	 
}

////////////////////////////////////////////////////////////////////////////////////////////

+ Buffer {
	
	// adjust this so mono files play in stereo, and new stereo array
	
	playPan { arg loop = false, mul = 1, pan=0;
		^{ var player;
			player = PlayBuf.ar(numChannels,bufnum,BufRateScale.kr(bufnum),
				loop: loop.binaryValue);
			loop.not.if(FreeSelfWhenDone.kr(player));
			Pan2.ar(player * mul, pan);
		}.play(server,fadeTime:0);
	}
	
	playMono { arg loop = false, mul = 1, offset=0;
		^{|mul=1| var player;
			player = PlayBuf.ar(1,bufnum,BufRateScale.kr(bufnum),
				startPos: numFrames*offset,
				loop: loop.binaryValue);
			loop.not.if(FreeSelfWhenDone.kr(player));
			[player * mul].dup;
		}.play(server,fadeTime:0, args:[\mul:mul]);
		
		
	}

	playStereo { arg loop = false, mul = 1, offset=0;
		^{|mul=1|  var player;
			player = [PlayBuf.ar(1,bufnum,BufRateScale.kr(bufnum),
				startPos: numFrames*offset,
				loop: loop.binaryValue)
				, PlayBuf.ar(1,bufnum+1,BufRateScale.kr(bufnum),
				startPos: numFrames*offset,
				loop: loop.binaryValue)];
			loop.not.if(FreeSelfWhenDone.kr(player));
			player * mul;
		}.play(server,fadeTime:0, args:[\mul:mul]);
	}	
	
}
 
 
/*

f = SoundFile.openRead("/Users/neilcosgrove/Desktop/acid tracks/walking from hassocks.aiff");
f.readByChunks(action: { |data|
	d = data.postln;
	f.close;
});

1048576/2
524288

*/

+ SoundFile {
	
	// by hjh. Thankyou, it helps a little.
	
	readByChunks { |startFrame = 0, numFrames = -1, chunkSize = 1048576,
					wait = 0.02, action, updateAction, floatArray|
	
		var data, chunk, readFrames = 0, readSize;
		
		if(numFrames < 0) { numFrames = this.numFrames - startFrame };
		chunkSize = chunkSize.round(this.numChannels).asInteger;
		this.seek(startFrame, 0);
		data = floatArray ? FloatArray.newClear(numFrames * this.numChannels);
		
		{
			while {
				readFrames < numFrames and: { // do nothing when we've read enough frames
					readSize = min(chunkSize, (numFrames - readFrames) * this.numChannels);
					chunk = FloatArray.newClear(readSize);
					this.readData(chunk);
					if(chunk.size > 0) { // at EOF, readData empties the array
						data.overWrite(chunk, readFrames * this.numChannels);
						readFrames = readFrames + (chunk.size / this.numChannels);
						true
					}{
						false
					} // exit at EOF
				}
			} {
				updateAction.value(data);
				wait.wait;
			};
			action.value(data);
		}.fork(AppClock);
	
	}
	
}
	
/*

Buffer.readChannelByChunk(s,"/Users/neilcosgrove/Desktop/acid tracks/walking from hassocks.aiff", 0, -1, 0, false, [0,1], nil, {|buf| b=buf; b.play; "done".postln}, 2**20);

*/
		
+ Buffer {	
		
	*readChannelByChunk{|server, path, startFrame = 0, numFrames = -1, bufStartFrame = 0,
					leaveOpen = false, channels, bufnum, action, chunkSize = 1048576|
	
		var sf, numCh, buf, incr = 0;
			
		sf = SoundFile.openRead(path);
	
		if(sf.isOpen) {
		
			numCh = sf.numChannels;
			if(numFrames < 0) {
				numFrames = sf.numFrames - startFrame;
			}{
				numFrames = min(numFrames, sf.numFrames - startFrame);
			};
		
			sf.close;

			buf = Buffer.alloc(server, numFrames, channels.size,nil,bufnum);
			
			{
				server.sync;
				(numFrames / chunkSize).roundUp.do{
					buf.readChannel(path,
						startFrame + incr,
						chunkSize.clip(0,numFrames-startFrame-incr),
						startFrame + incr,
						leaveOpen, channels);
					server.sync;
					0.02.wait;
					incr = incr + chunkSize;
				};
				action.value(buf);
			}.fork(AppClock);
		
			^buf;
		
		}
			
	}
	
}


