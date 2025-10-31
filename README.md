# OpenCV Demo

![Demo](demo.gif)

This is one of my very few android project which I built. It does a simple thing! Open camera and draw a circle. 
It is for a company assessment. 

Did it! I used opencv using ndk. 



I have made image modification projects, [post_maker: Post Maker helps you to make post for instagram and other social media apps easily and in massive amount.](https://github.com/PiyushXCoder/post_maker)

But this time I had 3 completely new things to learn in very tille time as of few hours. Android, Camera2 API and Opencv. 

After scrolling stackoverflow and gemini I finally made it!

How it works? Simple. Video is just stream of images. Images can be edited easily!



Now what about camera2 api? I am not sure either. I some how managed to get it working by guess work. I got a kotlin implementation, which helped me lot in the way 

https://proandroiddev.com/understanding-camera2-api-from-callbacks-part-1-5d348de65950

Even if I got it working I didn't knew how do I send image to c++, like its an object. I thought of making it a 2d matrix, but thankfully gemini came to rescue with some silli implementation which never worked. but it game me structure to follow. 

Well some how I have written the code for opencv. 

One of the thing which still confused me is why opencv is responsible to draw. I need more time to tinker. 



## What is missing?

Web, I want to include a video stream to web browser, but, pulling this will require me time to understand how do I add something like webrtc in android. 

## Architecture

It is a 2 file thing, I open camera, image in form of surface, send it to opencv, and opencv draws it. 

If android was not involved, I might have a better explanantion.

## How to setup?

I just copy paste whole NDK. Repository is very big because of it. 

It is very primitive way of doing it. But it works. I wish I could have used something like vcpkg. But not sure if its available for android. 

## Running?

Simply build and run using android sdk. It is a gradle project so you can use gradle commads too.
