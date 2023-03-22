import Flutter
import UIKit
import ReplayKit
import Photos

public class SwiftFlutterScreenRecordingPlugin: NSObject, FlutterPlugin {
    
let recorder = RPScreenRecorder.shared()

var videoWriter : AVAssetWriter?

var audioInput:AVAssetWriterInput!
var videoWriterInput : AVAssetWriterInput?
var nameVideo: String = ""
var recordAudio: Bool = false;
var myResult: FlutterResult?
let screenSize = UIScreen.main.bounds
    var videoSegments:[URL] = []
    
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "flutter_screen_recording", binaryMessenger: registrar.messenger())
    let instance = SwiftFlutterScreenRecordingPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {

    if(call.method == "startRecordScreen"){
         myResult = result
         let args = call.arguments as? Dictionary<String, Any>

         self.recordAudio = (args?["audio"] as? Bool)!
         self.nameVideo = (args?["name"] as? String)!+".mp4"
         startRecording()

    }else if(call.method == "stopRecordScreen"){
        if(videoWriter != nil){
            stopRecording { [weak self] in
                guard let self = self else { return }
                
                // Merge the video segments
                self.mergeVideoSegments(segments: self.videoSegments) { result in
                    switch result {
                    case .success(let mergedVideoURL):
                        print("Merged video URL: \(mergedVideoURL)")
                        
                        // Save the merged video to the Photos Library
                        PHPhotoLibrary.shared().performChanges({
                            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: mergedVideoURL)
                        }) { saved, error in
                            if saved {
                                print("The merged video was successfully saved")
                            } else {
                                print("Failed to save the merged video")
                                if let error = error {
                                    print("Error: \(error.localizedDescription)")
                                }
                            }
                        }
                        
                    case .failure(let error):
                        print("Failed to merge video segments")
                        print("Error: \(error.localizedDescription)")
                    }
                }
            }

            let documentsPath = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true)[0] as NSString
            result(String(documentsPath.appendingPathComponent(nameVideo)))
        }
         result("")
    } else if (call.method=="pauseRecordScreen") {
        pauseRecording{
            result(true)
        }
        
        
    }
      else if (call.method=="resumeRecordScreen") {
          resumeRecording()
      }
  }
    
    func pauseRecording(completion: @escaping () -> Void) {
        stopRecording {
            print("Recording paused")
            completion()
        }
    }
    
    @objc func resumeRecording() {
        startRecording()
    }


    @objc func startRecording() {

        // Use ReplayKit to record the screen
        // Create a unique name for each video segment using the current timestamp
        let timestamp = Int(Date().timeIntervalSince1970)
        let nameVideo = "video_segment_\(timestamp).mp4"

        // Create the file path to write to
        let documentsPath = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true)[0] as NSString
        let videoOutputURL = URL(fileURLWithPath: documentsPath.appendingPathComponent(nameVideo))

        // Add the URL to the videoSegments array
        self.videoSegments.append(videoOutputURL)

        //Check the file does not already exist by deleting it if it does
        do {
            try FileManager.default.removeItem(at: videoOutputURL)
        } catch {}

        do {
            try videoWriter = AVAssetWriter(outputURL: videoOutputURL, fileType: AVFileType.mp4)
        } catch let writerError as NSError {
            print("Error opening video file", writerError);
            videoWriter = nil;
            return;
        }

        //Create the video settings
        if #available(iOS 11.0, *) {
            
            var codec = AVVideoCodecType.jpeg;
            
            if(recordAudio){
                codec = AVVideoCodecType.h264;
            }
            
            let videoSettings: [String : Any] = [
                AVVideoCodecKey  : codec,
                AVVideoWidthKey  : screenSize.width,
                AVVideoHeightKey : screenSize.height
            ]
                        
            if(recordAudio){
                
                let audioOutputSettings: [String : Any] = [
                    AVNumberOfChannelsKey : 2,
                    AVFormatIDKey : kAudioFormatMPEG4AAC,
                    AVSampleRateKey: 44100,
                ]
                
                audioInput = AVAssetWriterInput(mediaType: AVMediaType.audio, outputSettings: audioOutputSettings)
                videoWriter?.add(audioInput)
            
            }


        //Create the asset writer input object whihc is actually used to write out the video
         videoWriterInput = AVAssetWriterInput(mediaType: AVMediaType.video, outputSettings: videoSettings);
         videoWriter?.add(videoWriterInput!);
            
        }

        //Tell the screen recorder to start capturing and to call the handler
        if #available(iOS 11.0, *) {
            
            if(recordAudio){
                RPScreenRecorder.shared().isMicrophoneEnabled=true;
            }else{
                RPScreenRecorder.shared().isMicrophoneEnabled=false;

            }
            
            RPScreenRecorder.shared().startCapture(
            handler: { (cmSampleBuffer, rpSampleType, error) in
                guard error == nil else {
                    //Handle error
                    print("Error starting capture");
                    self.myResult!(false)
                    return;
                }

                switch rpSampleType {
                case RPSampleBufferType.video:
                    print("writing sample....");
                    if self.videoWriter?.status == AVAssetWriter.Status.unknown {

                        if (( self.videoWriter?.startWriting ) != nil) {
                            print("Starting writing");
                            self.myResult!(true)
                            self.videoWriter?.startWriting()
                            self.videoWriter?.startSession(atSourceTime:  CMSampleBufferGetPresentationTimeStamp(cmSampleBuffer))
                        }
                    }

                    if self.videoWriter?.status == AVAssetWriter.Status.writing {
                        if (self.videoWriterInput?.isReadyForMoreMediaData == true) {
                            print("Writting a sample");
                            if  self.videoWriterInput?.append(cmSampleBuffer) == false {
                                print(" we have a problem writing video")
                                self.myResult!(false)
                            }
                        }
                    }


                default:
                   print("not a video sample, so ignore");
                }
            } ){(error) in
                        guard error == nil else {
                           //Handle error
                           print("Screen record not allowed");
                           self.myResult!(false)
                           return;
                       }
                   }
        } else {
            //Fallback on earlier versions
        }
    }

    @objc func stopRecording(completion: @escaping () -> Void) {
        if #available(iOS 11.0, *) {
            RPScreenRecorder.shared().stopCapture { error in
                print("stopping recording")

                // Finish writing video and audio inputs
                self.videoWriterInput?.markAsFinished()
                self.audioInput?.markAsFinished()

                // Finish writing the video segment
                self.videoWriter?.finishWriting {
                    print("finished writing video")
                    completion()
                }
            }
        } else {
            // Fallback on earlier versions
        }
    
}
    func mergeVideoSegments(segments: [URL], completion: @escaping (Result<URL, Error>) -> Void) {
        let mixComposition = AVMutableComposition()
        var totalDuration: CMTime = kCMTimeZero

        for segment in segments {
            let asset = AVAsset(url: segment)
            guard let videoTrack = asset.tracks(withMediaType: .video).first else { continue }

            let compositionVideoTrack = mixComposition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
            do {
                try compositionVideoTrack?.insertTimeRange(CMTimeRangeMake(kCMTimeZero, asset.duration), of: videoTrack, at: totalDuration)
                totalDuration = CMTimeAdd(totalDuration, asset.duration)
            } catch {
                completion(.failure(error))
                return
            }
        }

        // Export the merged video
        let outputURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("merged_video.mp4")

        if let exporter = AVAssetExportSession(asset: mixComposition, presetName: AVAssetExportPresetHighestQuality) {
            exporter.outputURL = outputURL
            exporter.outputFileType = .mp4
            exporter.shouldOptimizeForNetworkUse = true

            exporter.exportAsynchronously {
                switch exporter.status {
                case .completed:
                    completion(.success(outputURL))
                case .failed, .cancelled:
                    if let error = exporter.error {
                        completion(.failure(error))
                    }
                default:
                    break
                }
            }
        }
    }
}
