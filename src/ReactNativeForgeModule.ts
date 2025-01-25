import { NativeModule, requireNativeModule } from "expo";

import { ReactNativeForgeModuleEvents } from "./ReactNativeForge.types";

interface VideoSize {
  width: number;
  height: number;
}

interface PaddingResult {
  outputPath: string;
  ratio: number;
}

declare class ReactNativeForgeModule extends NativeModule<ReactNativeForgeModuleEvents> {
  getAllFrames(
    inputPath: string,
    outputFolder: string,
    duration: number,
    size?: VideoSize
  ): Promise<string[]>;

  getFrame(
    inputPath: string,
    outputPath: string,
    timestamp: number,
    quality?: number,
    size?: VideoSize
  ): Promise<string>;

  padToRatio(
    inputPath: string,
    outputPath: string,
    targetRatio: number
  ): Promise<PaddingResult>;

  getDuration(inputPath: string): Promise<number>;
}

export default requireNativeModule<ReactNativeForgeModule>("ReactNativeForge");
