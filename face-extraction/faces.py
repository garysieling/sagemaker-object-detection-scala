import dlib

import numpy as np
import cv2
import os, sys
from PIL import Image
detector = dlib.get_frontal_face_detector()


margin = 50
idx = 0

rootdir = "/data/images/sonny_saleigha/"

for subdir, dirs, files in os.walk(rootdir):
  for file in files:
    img = dlib.load_rgb_image(subdir + "/" + file)
    image_width = img.shape[0]
    image_height = img.shape[1]

    dets = detector(img, 1)

    for i, d in enumerate(dets):
      dstFile = "/data/images/kid_faces/" + str(idx) + ".jpg"

      try:
        print("Detection {}: Left: {} Top: {} Right: {} Bottom: {}".format(
          i, d.left(), d.top(), d.right(), d.bottom()))

        pos_start = tuple([max(d.left() - margin, 0), max(d.top() - margin, 0)])
        pos_end = tuple([min(d.right() + margin, image_width - 1), min(d.bottom() + margin, image_height - 1)])

        height = pos_end[1] - pos_start[1]
        width =  pos_end[0] - pos_start[0]

        print(pos_start)
        print(pos_end)
        print(width)
        print(height)
        print(idx)

        if (width <= 0):
          continue
      
        if (height <= 0):
          continue

        img_blank = np.zeros((height, width, 3), np.uint8)
        for i in range(height):
          for j in range(width):
            img_blank[i][j] = img[pos_start[1] + i][pos_start[0] + j]

        face = cv2.cvtColor(img_blank, cv2.COLOR_BGR2RGB)
        cv2.imwrite(dstFile, face)
        idx = idx + 1
      except:
        pass
