package dev.unwritten;
public class AnomalyLifetimeTest {
 public static void main(String[] args){
  if(AnomalyLifetime.expired(0,225,true,true))throw new AssertionError("premature expiry");
  if(!AnomalyLifetime.expired(200,225,true,true))throw new AssertionError("timeout");
  if(!AnomalyLifetime.expired(5,64,true,true))throw new AssertionError("near");
  if(!AnomalyLifetime.expired(5,1024,true,true))throw new AssertionError("far");
  if(!AnomalyLifetime.expired(5,225,false,true)||!AnomalyLifetime.expired(5,225,true,false))throw new AssertionError("eligibility");
  if(!AnomalyLifetime.expired(5,Double.NaN,true,true))throw new AssertionError("invalid distance");
 }
}
