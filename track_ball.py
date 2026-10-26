import sys
import os
import time
from collections import deque
import cv2
import torch
from ultralytics import YOLO


def resolve_path(path_str):
    """Resolve file path relative to current working directory or script directory."""
    if os.path.exists(path_str):
        return path_str
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.join(script_dir, path_str)
    if os.path.exists(candidate):
        return candidate
    return path_str


def process_ball_tracking(camera_role, video_path):
    print(f"[AI MODEL] Initializing ball tracking pipeline...")
    print(f"[AI MODEL] Camera Angle: {camera_role}")
    print(f"[AI MODEL] Processing Video: {video_path}")

    video_path = resolve_path(video_path)
    if not os.path.exists(video_path):
        print(f"[ERROR] Video file does not exist: {video_path}", file=sys.stderr)
        sys.exit(1)

    # 1. Device selection: automatically use CUDA GPU if available, otherwise CPU
    if torch.cuda.is_available():
        device = "cuda"
        print("[INFO] Hardware: GPU detected -> Using CUDA with FP16")
    else:
        device = "cpu"
        print("[INFO] Hardware: GPU not detected -> Using CPU")

    # 2. Model loading & validation
    model_path = resolve_path("models/best.pt")
    if not os.path.exists(model_path):
        print(f"[ERROR] Model weights not found at: '{model_path}'", file=sys.stderr)
        print("[ERROR] Please place trained weights into 'models/best.pt'.", file=sys.stderr)
        sys.exit(1)

    print(f"[INFO] Loading cricket ball model: {model_path}")
    model = YOLO(model_path)
    ball_class_id = 0

    # 3. Open video source
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        print(f"[ERROR] Could not open video source: {video_path}", file=sys.stderr)
        sys.exit(1)

    frame_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    frame_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    input_fps = cap.get(cv2.CAP_PROP_FPS)
    if input_fps <= 0 or input_fps > 120:
        input_fps = 30.0

    print(f"[INFO] Resolution: {frame_width}x{frame_height} | FPS: {input_fps:.1f}")

    # 4. Setup output video writer
    output_path = os.path.splitext(video_path)[0] + "_trajectory.mp4"
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(output_path, fourcc, input_fps, (frame_width, frame_height))
    print(f"[INFO] Saving processed video to: {output_path}")

    # 5. Trajectory and detection parameters
    trajectory = deque(maxlen=100)
    confidence_threshold = 0.30

    print("[AI MODEL] Detecting spherical motion & pitched trajectory...")

    try:
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break

            # Run YOLO inference
            predict_kwargs = {
                "conf": confidence_threshold,
                "device": device,
                "verbose": False,
            }
            if device == "cuda":
                predict_kwargs["half"] = True

            results = model.predict(frame, **predict_kwargs)

            best_box = None
            best_conf = 0.0

            if len(results) > 0 and results[0].boxes is not None:
                boxes = results[0].boxes
                for box in boxes:
                    cls_id = int(box.cls[0].item())
                    conf = float(box.conf[0].item())

                    if cls_id == ball_class_id and conf >= confidence_threshold:
                        if conf > best_conf:
                            best_conf = conf
                            best_box = box.xyxy[0].cpu().numpy()

            if best_box is not None:
                x1, y1, x2, y2 = map(int, best_box)
                detected_cx = int((x1 + x2) / 2)
                detected_cy = int((y1 + y2) / 2)

                # Add point to trajectory
                trajectory.append((detected_cx, detected_cy))

                # Draw bounding box and center point
                cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
                cv2.circle(frame, (detected_cx, detected_cy), 5, (0, 0, 255), -1)
                cv2.putText(
                    frame,
                    f"Ball: {best_conf:.2f}",
                    (x1, max(y1 - 8, 15)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.5,
                    (0, 255, 0),
                    2,
                    cv2.LINE_AA,
                )

            # Draw trajectory path
            for i in range(1, len(trajectory)):
                cv2.line(frame, trajectory[i - 1], trajectory[i], (0, 255, 255), 2)

            # Write frame to output file (headless execution, no cv2.imshow)
            writer.write(frame)

    except Exception as e:
        print(f"[ERROR] Exception during tracking loop: {e}", file=sys.stderr)
        sys.exit(1)

    finally:
        cap.release()
        if writer is not None:
            writer.release()
        print(f"[AI MODEL] Complete! Trajectory rendered to: {output_path}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python track_ball.py <camera_role> <video_path>", file=sys.stderr)
        sys.exit(1)

    cam_role = sys.argv[1]
    vid_path = sys.argv[2]
    process_ball_tracking(cam_role, vid_path)