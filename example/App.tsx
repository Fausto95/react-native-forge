import * as Crypto from "expo-crypto";
import * as FileSystem from "expo-file-system";
import * as ImagePicker from "expo-image-picker";
import { useState, useEffect } from "react";
import {
  Button,
  SafeAreaView,
  ScrollView,
  Text,
  View,
  StyleSheet,
  Image,
  FlatList,
  Dimensions,
} from "react-native";
import ReactNativeForge from "react-native-forge";

export default function App() {
  const [progress, setProgress] = useState(0);
  const [frames, setFrames] = useState<string[]>([]);
  const [singleFrame, setSingleFrame] = useState<string>("");
  const [videoDuration, setVideoDuration] = useState(0);

  const resetStates = () => {
    setProgress(0);
    setFrames([]);
    setSingleFrame("");
    setVideoDuration(0);
  };

  const handleVideoProcess = async () => {
    resetStates();
    try {
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ImagePicker.MediaTypeOptions.Videos,
      });

      if (!result.canceled && result.assets[0]) {
        const videoUri = result.assets[0].uri;
        const uuid = Crypto.randomUUID();

        const framesDir = `${FileSystem.cacheDirectory}video_frames_${uuid}/`;
        try {
          const dirInfo = await FileSystem.getInfoAsync(framesDir);
          if (dirInfo.exists) {
            await FileSystem.deleteAsync(framesDir);
          }
        } catch (e) {
          console.error("Error clearing cache:", e);
        }

        const duration = await ReactNativeForge.getDuration(videoUri);

        setVideoDuration(duration);

        // Ensure video URI is properly formatted for Android
        const properVideoUri = videoUri.startsWith("file://")
          ? videoUri
          : "file://" + videoUri;

        const generatedFrames = await ReactNativeForge.getAllFrames(
          properVideoUri,
          framesDir,
          duration
          // { width: 640, height: 480 }
        );

        // Convert frame paths to proper URIs
        const frameUris = generatedFrames.map((frame) =>
          frame.startsWith("file://") ? frame : "file://" + frame
        );

        setFrames(frameUris);
        console.log("Generated frames:", frameUris);

        const frameOutputPath = `${FileSystem.cacheDirectory}single_frame_${uuid}.jpg`;
        const paddedOutputPath = `${FileSystem.cacheDirectory}padded_video_${uuid}.mp4`;

        const frame = await ReactNativeForge.getFrame(
          properVideoUri,
          frameOutputPath,
          5,
          80
          // { width: 1280, height: 820 }
        );
        setSingleFrame(frame.startsWith("file://") ? frame : "file://" + frame);
        console.log("Generated frame:", frame);

        const paddedVideo = await ReactNativeForge.padToRatio(
          properVideoUri,
          paddedOutputPath,
          16 / 9
        );
        console.log("Padded video:", paddedVideo);
      }
    } catch (error) {
      console.error("Error processing video:", error);
    }
  };

  const renderFrame = ({ item }: { item: string }) => (
    <Image
      source={{ uri: item }}
      style={styles.frameImage}
      resizeMode="contain"
    />
  );

  useEffect(() => {
    const subscription = ReactNativeForge.addListener(
      "onProgress",
      (event: { progress: number }) => {
        setProgress(event.progress);
      }
    );

    return () => {
      subscription.remove();
    };
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>React Native Forge</Text>
        <Button title="Choose a video" onPress={handleVideoProcess} />

        {progress > 0 && progress < 1 && (
          <View style={styles.progressContainer}>
            <Text style={styles.subtitle}>
              Processing: {Math.round(progress * 100)}%
            </Text>
            <View style={styles.progressBar}>
              <View
                style={[styles.progressFill, { width: `${progress * 100}%` }]}
              />
            </View>
          </View>
        )}

        {frames.length > 0 ? (
          <Text style={styles.subtitle}>Frames: {frames.length}</Text>
        ) : null}

        {frames.length > 0 ? <Text>Duration: {videoDuration}</Text> : null}

        {singleFrame ? (
          <View style={styles.singleFrameContainer}>
            <Text style={styles.subtitle}>Single Frame:</Text>
            <Image
              source={{ uri: singleFrame }}
              style={styles.singleFrame}
              resizeMode="contain"
            />
          </View>
        ) : null}

        {frames.length > 0 ? (
          <View style={styles.framesContainer}>
            <Text style={styles.subtitle}>All Frames:</Text>
            <FlatList
              data={frames}
              renderItem={renderFrame}
              keyExtractor={(item, index) => `frame-${index}`}
              horizontal
              showsHorizontalScrollIndicator
              style={styles.framesList}
            />
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const { width } = Dimensions.get("window");

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#fff",
  },
  content: {
    padding: 20,
    alignItems: "center",
  },
  title: {
    fontSize: 24,
    fontWeight: "bold",
    marginBottom: 20,
  },
  subtitle: {
    fontSize: 18,
    fontWeight: "600",
    marginTop: 20,
    marginBottom: 10,
  },
  singleFrameContainer: {
    width: "100%",
    alignItems: "center",
    marginTop: 20,
  },
  singleFrame: {
    width: width - 40,
    height: 200,
    borderRadius: 8,
    backgroundColor: "#f0f0f0",
  },
  framesContainer: {
    width: "100%",
    marginTop: 20,
  },
  framesList: {
    flexGrow: 0,
  },
  frameImage: {
    width: 160,
    height: 120,
    marginRight: 10,
    borderRadius: 8,
    backgroundColor: "#f0f0f0",
  },
  progressContainer: {
    justifyContent: "center",
    alignItems: "center",
    width: "100%",
    marginTop: 20,
  },
  progressBar: {
    width: "80%",
    height: 10,
    backgroundColor: "#f0f0f0",
    borderRadius: 5,
    overflow: "hidden",
  },
  progressFill: {
    height: "100%",
    backgroundColor: "#007AFF",
  },
});
